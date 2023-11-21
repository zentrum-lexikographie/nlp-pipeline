'''Command line interface for the custom spaCy pipeline.'''
import sys

from zdl.spacy import pipeline

def main():
    nlp = pipeline(dwdsmor_path='resources/dwdsmor/dwdsmor.ca')
    dep_docs, ner_docs = nlp([' '.join(sys.argv[1:])])
    for dep_doc, ner_doc in zip(dep_docs, ner_docs):
        print(repr((dep_doc.to_json(), ner_doc.to_json())))

if __name__ == '__main__':
    main()
