'''spaCy-based NLP pipeline.'''
from functools import partial
from itertools import repeat

import spacy
import spacy.language
import spacy.tokens
import zdl.spacy.colloc
import zdl.spacy.dwdsmor
import zdl.korap

def _tokens_to_doc(vocab, tokens):
    words, spaces, sent_starts = tokens
    return spacy.tokens.Doc(
        vocab, words=words, spaces=spaces, sent_starts=sent_starts
    )

def _nop_tokenizer(doc):
    return doc


def _annotate(dep_tagger, ner_tagger, texts):
    texts = [zdl.korap.tokenize(t) for t in texts]
    dep_docs = [_tokens_to_doc(dep_tagger.vocab, tokens) for tokens in texts]
    dep_docs = dep_tagger.pipe(dep_docs)
    if ner_tagger is None:
        ner_docs = repeat(None)
    else:
        ner_docs = [_tokens_to_doc(ner_tagger.vocab, tokens) for tokens in texts]
        ner_docs = ner_tagger.pipe(ner_docs)
    return (dep_docs, ner_docs)

def pipeline(ner=True, model_type='lg', gpu=False, dwdsmor_path=None):
    '''Creates a custom spaCy pipeline for CPU or GPU architecture.'''
    if gpu:
        spacy.require_gpu()
    dep_tagger = spacy.load(f'de_dwds_dep_hdt_{model_type}')
    dep_tagger.tokenizer = _nop_tokenizer
    if dwdsmor_path is not None:
        dep_tagger.add_pipe('dwdsmor', config={'transducer_path': dwdsmor_path})
    dep_tagger.add_pipe('dwds_colloc')
    ner_tagger = None
    if ner is True:
        ner_tagger = spacy.load(f'de_dwds_ner_{model_type}')
        ner_tagger.tokenizer = _nop_tokenizer
    return partial(_annotate, dep_tagger, ner_tagger)
