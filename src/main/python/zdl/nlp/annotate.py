import argparse
import itertools
import json
import logging
import multiprocessing
import os

import conllu
import conllu.parser
import dwdsmor
import dwdsmor.tag.hdt
import spacy
import spacy.tokens
from lingua import Language, LanguageDetectorBuilder
from tqdm import tqdm

from .colloc import extract_collocs
from .conllu import is_space_after, serialize, text
from .models import load_dwdsmor, load_spacy

logger = logging.getLogger(__name__)


def spacy_doc(nlp, s):
    return spacy.tokens.Doc(
        nlp.vocab,
        words=tuple(t["form"] for t in s),
        spaces=tuple(is_space_after(t) for t in s),
    )


def spacy_nlp(nlp, sentences, batch_size=128, **kwargs):
    doc_sents, sentences = itertools.tee(sentences, 2)
    docs = (spacy_doc(nlp, s) for s in doc_sents)
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
        yield s


def detect_languages(lang_detector, sentences, batch_size=4096):
    sentences, sents = itertools.tee(sentences, 2)
    texts = (text(s) for s in sentences)
    texts = itertools.batched(texts, batch_size)
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


def lemmatize(lemmatizer, sentences):
    for sentence in sentences:
        for token in sentence:
            token_form = token["form"]
            token_lemma = token["lemma"]
            token_pos = token["xpos"]
            token_morph = token["feats"] or {}
            token_criteria = {
                k: frozenset(v) if v else None
                for k, v in dwdsmor.tag.hdt.criteria(
                    token_pos,
                    token_morph.get("Number"),
                    token_morph.get("Gender"),
                    token_morph.get("Case"),
                    token_morph.get("Person"),
                    token_morph.get("Tense"),
                    token_morph.get("Degree"),
                    token_morph.get("Mood"),
                    token_morph.get("VerbForm"),
                ).items()
            }
            if token_lemma == "_":
                token_lemma = token["lemma"] = token_form
            dwdsmor_result = lemmatizer(token_form, **token_criteria)
            if not dwdsmor_result:
                continue
            dwdsmor_lemma = dwdsmor_result.analysis
            if token_lemma == dwdsmor_lemma:
                continue
            # make a POS match mandatory
            if dwdsmor_result.pos not in dwdsmor.tag.hdt.pos_map[token_pos]:
                continue
            token["lemma"] = dwdsmor_lemma
        yield sentence


def init_annotate(init_batch_size=128, accurate=True, gpus=None, dwdsmor_dwds=False):
    global batch_size, nlp, lemmatizer, lang_detector
    batch_size = init_batch_size
    gpu_id = None
    if gpus:
        proc_id = multiprocessing.current_process()._identity or (0,)
        proc_n, *_ = proc_id
        gpu_id = gpus[proc_n % len(gpus)]
    nlp = load_spacy(accurate, gpu_id)
    lemmatizer = load_dwdsmor("dwds" if dwdsmor_dwds else "open")
    languages = (Language.ENGLISH, Language.FRENCH, Language.GERMAN, Language.LATIN)
    lang_detector = (
        LanguageDetectorBuilder.from_languages(*languages)
        .with_preloaded_language_models()
        .with_low_accuracy_mode()
        .build()
    )


def annotate(chunks):
    global batch_size, nlp, lemmatizer, lang_detector
    sentences = (s for chunk in chunks for s in conllu.parse(chunk))
    sentences = spacy_nlp(nlp, sentences, batch_size)
    sentences = lemmatize(lemmatizer, sentences)
    sentences = detect_languages(lang_detector, sentences, batch_size)
    sentences = (collapse_phrasal_verbs(s) for s in sentences)
    sentences = (extract_collocs(s) for s in sentences)
    return tuple(serialize(s) for s in sentences)


def read_chunks(lines):
    chunk = ""
    for line in lines:
        if line == "\n":
            if chunk:
                yield chunk
                chunk = ""
        else:
            chunk += line
    if chunk:
        yield chunk


arg_parser = argparse.ArgumentParser(description="Add linguistic annotations")
arg_parser.add_argument(
    "-b",
    "--batch-size",
    help="# of sentences to process in one batch (128 by default)",
    type=int,
)
arg_parser.add_argument(
    "-c",
    "--concurrency",
    help="# of parallel annotation pipelines (1 by defaul)",
    type=int,
    default="-1",
)
arg_parser.add_argument(
    "-d", "--dwdsmor-dwds", help="Use DWDS-Edition of DWDSmor", action="store_true"
)
arg_parser.add_argument(
    "-f", "--fast", help="Use CPU-optimized model", action="store_true"
)
arg_parser.add_argument(
    "-g", "--gpu", help="IDs of GPUs to use (default: none)", type=int, action="append"
)
arg_parser.add_argument(
    "-i",
    "--input-file",
    help="input CoNLL-U file to annotate",
    type=argparse.FileType("r"),
    default="-",
)
arg_parser.add_argument(
    "-o",
    "--output-file",
    help="output CoNLL-U file with (updated) annotations",
    type=argparse.FileType("w"),
    default="-",
)
arg_parser.add_argument("-p", "--progress", help="Show progress", action="store_true")


def main():
    args = arg_parser.parse_args()
    progress = None
    if args.progress:
        progress = tqdm(
            desc="Annotating – POS, Deps, Lemma, NER, Language, Collocations",
            unit=" sentences",
            unit_scale=True,
        )
    pool = None
    try:
        batch_size = args.batch_size or 128
        init_args = (batch_size, not args.fast, args.gpu, args.dwdsmor_dwds)
        chunks = read_chunks(args.input_file)
        batches = itertools.batched(chunks, batch_size)
        n_procs = args.concurrency
        if n_procs >= 0:
            n_procs = n_procs or os.process_cpu_count()
            mp_ctx = multiprocessing.get_context("forkserver")
            pool = mp_ctx.Pool(n_procs, init_annotate, init_args)
            batches = pool.imap(annotate, batches)
        else:
            init_annotate(*init_args)
            batches = (annotate(batch) for batch in batches)
        for batch in batches:
            for chunk in batch:
                args.output_file.write(chunk)
            if progress is not None:
                progress.update(len(batch))
    finally:
        if pool:
            pool.terminate()


if __name__ == "__main__":
    main()
