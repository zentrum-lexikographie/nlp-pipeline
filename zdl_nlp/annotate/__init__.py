import atexit
import json
import multiprocessing
import os
from dataclasses import dataclass
from itertools import batched, tee

import conllu
import conllu.parser
import dwdsmor
import dwdsmor.tag.hdt
import gdex
import spacy
import spacy.tokens
import thinc.api
from lingua import Language, LanguageDetectorBuilder

from ..colloc import extract_collocs
from ..conllu import is_space_after, text


def spacy_pipe(nlp, sentences, batch_size=128, **kwargs):
    doc_sents, sentences = tee(sentences, 2)
    docs = (
        spacy.tokens.Doc(
            nlp.vocab,
            words=tuple(t["form"] for t in s),
            spaces=tuple(is_space_after(t) for t in s),
            sent_starts=tuple(ti == 0 for ti, t_ in enumerate(s)),
        )
        for s in doc_sents
    )
    docs = nlp.pipe(docs, batch_size=batch_size, **kwargs)
    for s, doc in zip(sentences, docs):
        for token, nlp_token in zip(s, doc):
            feats = (
                conllu.parser.parse_dict_value(str(nlp_token.morph))
                if nlp_token.morph
                else None
            )
            is_root = nlp_token.dep_ == "ROOT"
            token.update(
                {
                    "lemma": nlp_token.lemma_,
                    "upos": nlp_token.pos_,
                    "xpos": nlp_token.tag_,
                    "feats": feats,
                    "head": 0 if is_root else nlp_token.head.i + 1,
                    "deprel": "root" if is_root else nlp_token.dep_,
                }
            )
        if doc.ents:
            s.metadata["entities"] = json.dumps(
                tuple(
                    (e.label_, *(i + 1 for i in range(e.start, e.end)))
                    for e in doc.ents
                )
            )
        if len(doc) > 0:
            doc = gdex.de_hdt(doc)
            doc_sent, *_ = doc.sents
            s.metadata["gdex"] = str(doc_sent._.gdex)
        yield s


def lingua_detect(lang_detector, sentences, batch_size=4096):
    sentences, sents = tee(sentences, 2)
    texts = (text(s) for s in sentences)
    texts = batched(texts, batch_size)
    langs = (
        lang
        for tb in texts
        for lang in lang_detector.detect_languages_in_parallel_of(tuple(tb))
    )
    for sentence, lang in zip(sents, langs):
        if lang and lang.iso_code_639_1:
            sentence.metadata["lang"] = lang.iso_code_639_1.name.lower()
        yield sentence


def collapse_phrasal_verbs(sentence):
    for token_index, token in enumerate(sentence):
        particle = token["form"].lower()
        if particle == "recht":
            continue
        if token["deprel"] != "compound:prt":
            continue
        if token["upos"] not in {"ADP", "ADJ", "ADV"}:
            continue
        head = sentence[token["head"] - 1]
        if not head or head["upos"] not in {"VERB", "AUX"}:
            continue
        verb = head["lemma"]
        if verb == "sein":
            continue
        head["misc"] = (head["misc"] or {}) | {
            "CompoundPrt": token_index + 1,
            "CompoundVerb": f"{particle}{verb}",
        }
    return sentence


def dwdsmor_lemmatize(lemmatizer, sentences):
    for sentence in sentences:
        sep_idxs = {t["head"] for t in sentence if t["deprel"] == "compound:prt"}
        for ti, token in enumerate(sentence, 1):
            token_form = token["form"]
            token_lemma = token.get("lemma")
            token_pos = token["xpos"]
            token_morph = token["feats"] or {}
            is_sep = ti in sep_idxs
            is_prt = token["deprel"] == "compound:prt"
            token_criteria = {
                k: frozenset(v) if v else None
                for k, v in dwdsmor.tag.hdt.criteria(
                    token_pos,
                    token_morph.get("Number"),
                    token_morph.get("Gender"),
                    token_morph.get("Case"),
                    token_morph.get("Person") or ("UnmPers" if is_sep else None),
                    token_morph.get("Tense"),
                    token_morph.get("Degree"),
                    token_morph.get("Mood"),
                    token_morph.get("VerbForm"),
                    "SEP" if (is_prt or is_sep) else None,
                ).items()
            }
            if not token_lemma:
                token_lemma = token["lemma"] = token_form
            dwdsmor_result = lemmatizer(token_form, **token_criteria)
            if not dwdsmor_result:
                token["misc"] = (token["misc"] or {}) | {"DWDSmor": "No"}
                continue
            dwdsmor_lemma = dwdsmor_result.analysis
            if token_lemma == dwdsmor_lemma and ti not in sep_idxs:
                continue
            # make a POS match mandatory
            if dwdsmor_result.pos not in dwdsmor.tag.hdt.pos_map[token_pos]:
                token["misc"] = (token["misc"] or {}) | {"DWDSmor": "No"}
                continue
            token["lemma"] = dwdsmor_lemma
        yield sentence


@dataclass
class Pipeline:
    gpus: tuple[int, ...] = tuple()
    batch_size: int = 128
    n_procs: int = -1

    def init(self):
        gpu_id = None
        if self.gpus:
            proc = multiprocessing.current_process()
            proc_id = proc._identity or (0,)
            proc_n, *_ = proc_id
            n_gpus = len(self.gpus)
            gpu_id = self.gpus[proc_n % n_gpus]
        if gpu_id is not None:
            thinc.api.set_gpu_allocator("pytorch")
            thinc.api.require_gpu(gpu_id)
        self.spacy = spacy.load("de_zdl_lg" if gpu_id is None else "de_zdl_dist")
        self.spacy.add_pipe("doc_cleaner")
        self.dwdsmor = dwdsmor.lemmatizer()
        self.lingua = (
            LanguageDetectorBuilder.from_languages(
                Language.ENGLISH, Language.FRENCH, Language.GERMAN, Language.LATIN
            )
            .with_preloaded_language_models()
            .with_low_accuracy_mode()
            .build()
        )
        return self

    def __call__(self, sentences):
        sentences = spacy_pipe(self.spacy, sentences, self.batch_size)
        sentences = dwdsmor_lemmatize(self.dwdsmor, sentences)
        sentences = lingua_detect(self.lingua, sentences, self.batch_size)
        sentences = (extract_collocs(s) for s in sentences)
        sentences = (collapse_phrasal_verbs(s) for s in sentences)
        return sentences


def _init_pipe(pipeline_):
    global pipeline
    pipeline = pipeline_.init()
    return pipeline


def create_pipe(*args, **kwargs):
    p = Pipeline(*args, **kwargs)
    if p.n_procs < 0:
        _init_pipe(p)
        return pipe

    n_procs = p.n_procs or len(os.sched_getaffinity(0))
    mp_ctx = multiprocessing.get_context("spawn")
    pool = mp_ctx.Pool(n_procs, _init_pipe, (p,))

    @atexit.register
    def terminate_pool():
        pool.terminate()

    def pooled_pipe(sentences, pool=pool):
        batches = batched(sentences, p.batch_size)
        for batch in pool.imap(as_tuple, batches):
            for s in batch:
                yield s

    return pooled_pipe


def pipe(sentences):
    return pipeline(sentences)


def as_tuple(sentences):
    return tuple(pipe(sentences))
