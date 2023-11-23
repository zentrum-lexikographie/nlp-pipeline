'''Command line interface for the custom spaCy pipeline.'''
import sys

import zdl.spacy.pipe

def main():
    nlp = zdl.spacy.pipe.create(dwdsmor_path='resources/dwdsmor/dwdsmor.ca')
    dep_docs, ner_docs = nlp([' '.join(sys.argv[1:])])
    for dep_doc, ner_doc in zip(dep_docs, ner_docs):
        print(repr((dep_doc.to_json(), ner_doc.to_json())))

if __name__ == '__main__':
    main()
