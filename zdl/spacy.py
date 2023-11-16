'''spaCy-based NLP pipeline.'''
import sys
from functools import partial
from itertools import repeat

import spacy
import spacy.tokens
#pylint: disable=unused-import
import zdl.dwdsmor
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

def pipeline(ner=True, gpu=False, dwdsmor_path=None):
    '''Creates a custom spaCy pipeline for CPU or GPU architecture.'''
    model_type = 'dist' if gpu else 'lg'
    if gpu:
        spacy.require_gpu()
    dep_tagger = spacy.load(f'de_dwds_dep_hdt_{model_type}')
    dep_tagger.tokenizer = _nop_tokenizer
    if dwdsmor_path is not None:
        dep_tagger.add_pipe("dwdsmor", config={'transducer_path': dwdsmor_path})
    ner_tagger = None
    if ner is True:
        ner_tagger = spacy.load(f'de_dwds_ner_{model_type}')
        ner_tagger.tokenizer = _nop_tokenizer
    return partial(_annotate, dep_tagger, ner_tagger)


def main():
    nlp = pipeline(dwdsmor_path='resources/dwdsmor/dwdsmor.ca')
    dep_docs, ner_docs = nlp([' '.join(sys.argv[1:])])
    for dep_doc, ner_doc in zip(dep_docs, ner_docs):
        print(repr((dep_doc.to_json(), ner_doc.to_json())))

if __name__ == '__main__':
    main()
