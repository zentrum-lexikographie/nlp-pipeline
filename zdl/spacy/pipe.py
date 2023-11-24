'''spaCy-based NLP pipeline.'''
from functools import partial
from itertools import repeat, tee

import spacy
import spacy.language
import spacy.tokens
import zdl.spacy.colloc
import zdl.spacy.dwdsmor

from zdl.log import logger

def _tokens_to_doc(vocab, tokens):
    words, spaces, sent_starts = tokens
    return spacy.tokens.Doc(
        vocab, words=words, spaces=spaces, sent_starts=sent_starts
    )

def _nop_tokenizer(doc):
    return doc

def _merge_ner(dep_docs, ner_docs):
    for dep_doc, ner_doc in zip(dep_docs, ner_docs):
        dep_doc.set_ents([
            spacy.tokens.Span(dep_doc, entity.start, entity.end, entity.label_)
            for entity in ner_doc.ents
        ])
        yield dep_doc

def _tokenize(korap, dep_tagger, ner_tagger, texts):
    if korap:
        texts = (zdl.korap.tokenize(t) for t in texts)
        dep_texts, ner_texts = tee(texts)
        dep_docs = (_tokens_to_doc(dep_tagger.vocab, t) for t in dep_texts)
        ner_docs = (_tokens_to_doc(ner_tagger.vocab, t) for t in ner_texts)
        return (dep_docs, ner_docs)
    else:
        dep_docs, ner_docs = tee(texts)
        return (dep_docs, ner_docs)

def _tag(dep_tagger, ner_tagger, custom_components, dep_docs, ner_docs):
    dep_docs = dep_tagger.pipe(dep_docs, disable=custom_components)
    ner_docs = ner_tagger.pipe(ner_docs) if ner_tagger is not None else None
    return _merge_ner(dep_docs, ner_docs) if ner_docs is not None else dep_docs

def _annotate(dep_tagger, dep_tagger_components, docs):
    return dep_tagger.pipe(
        docs, disable=dep_tagger_components, batch_size=32, n_process=1
    )

def _pipe(korap, dep_tagger, ner_tagger, dep_tagger_components,
          custom_components, texts):
    dep_docs, ner_docs = _tokenize(korap, dep_tagger, ner_tagger, texts)
    docs = _tag(dep_tagger, ner_tagger, custom_components, dep_docs, ner_docs)
    docs = _annotate(dep_tagger, dep_tagger_components, docs)
    return docs

def create(ner=True, model_type='lg', korap=True, gpu=False, dwdsmor_path=None):
    '''Creates a custom spaCy pipeline for CPU or GPU architecture.'''
    # GPU setup
    if gpu:
        # TODO: configure GPU device
        spacy.require_gpu()

    # POS/Dependency tagger configuration, including custom components
    dep_tagger = spacy.load(f'de_dwds_dep_hdt_{model_type}')
    dep_tagger_components = list(dep_tagger.pipe_names)
    if dwdsmor_path is not None:
        dep_tagger.add_pipe('dwdsmor', config={'transducer_path': dwdsmor_path})
    dep_tagger.add_pipe('dwds_colloc')
    custom_components = list(
        set(dep_tagger.pipe_names).difference(dep_tagger_components)
    )

    # NER tagger configuration
    ner_tagger = None
    if ner is True:
        ner_tagger = spacy.load(f'de_dwds_ner_{model_type}')

    # Tokenizer configuration
    if korap is True:
        import zdl.korap
        dep_tagger.tokenizer = _nop_tokenizer
        if ner_tagger is not None:
            ner_tagger.tokenizer = _nop_tokenizer
    else:
        dep_tagger.add_pipe('sentencizer', first=True)
        if ner is True:
            ner_tagger.add_pipe('sentencizer', first=True)

    return partial(
        _pipe,
        korap,
        dep_tagger, ner_tagger,
        dep_tagger_components, custom_components
    )
