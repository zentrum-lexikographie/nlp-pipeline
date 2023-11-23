'''spaCy-based NLP pipeline.'''
from functools import partial
from itertools import repeat

import spacy
import spacy.language
import spacy.tokens
import zdl.spacy.colloc
import zdl.spacy.dwdsmor

def _tokens_to_doc(vocab, tokens):
    words, spaces, sent_starts = tokens
    return spacy.tokens.Doc(
        vocab, words=words, spaces=spaces, sent_starts=sent_starts
    )

def _nop_tokenizer(doc):
    return doc


def _annotate(korap, dep_tagger, ner_tagger, texts):
    dep_docs = texts
    ner_docs = texts
    if korap:
        texts = [zdl.korap.tokenize(t) for t in texts]
        dep_docs = [_tokens_to_doc(dep_tagger.vocab, tokens) for tokens in texts]
        if ner_tagger is not None:
            ner_docs = [_tokens_to_doc(ner_tagger.vocab, tokens) for tokens in texts]
    dep_docs = dep_tagger.pipe(dep_docs)
    ner_docs = ner_tagger.pipe(ner_docs) if ner_tagger is not None else repeat(None)
    return (dep_docs, ner_docs)

def create(ner=True, model_type='lg', korap=True, gpu=False, dwdsmor_path=None):
    '''Creates a custom spaCy pipeline for CPU or GPU architecture.'''
    if gpu:
        spacy.require_gpu()
    dep_tagger = spacy.load(f'de_dwds_dep_hdt_{model_type}')
    if dwdsmor_path is not None:
        dep_tagger.add_pipe('dwdsmor', config={'transducer_path': dwdsmor_path})
    dep_tagger.add_pipe('dwds_colloc')
    ner_tagger = None
    if ner is True:
        ner_tagger = spacy.load(f'de_dwds_ner_{model_type}')
    if korap is True:
        import zdl.korap
        dep_tagger.tokenizer = _nop_tokenizer
        if ner_tagger is not None:
            ner_tagger.tokenizer = _nop_tokenizer
    else:
        dep_tagger.add_pipe('sentencizer', first=True)
        ner_tagger.add_pipe('sentencizer', first=True)
    return partial(_annotate, korap, dep_tagger, ner_tagger)
