"""Annotation pipeline."""
import sys

import lxml.etree as ET

import zdl.spacy.pipe
import zdl.tei

#pylint: disable=c-extension-no-member
def annotate_tei_docs(nlp, tei_docs):
    '''Annotates a batch of <TEI/> documents.'''
    chunks = [c for d in tei_docs for c in zdl.tei.iter_chunks(d)]
    texts = [zdl.tei.extract_text(c) for c in chunks]
    dep_docs, ner_docs = nlp([t.content for t in texts])
    for chunk, text, dep_doc, ner_doc in zip(chunks, texts, dep_docs, ner_docs):
        zdl.tei.serialize_annotations(chunk, text, dep_doc, ner_doc)
    return tei_docs


def annotate_corpus(nlp, xml_in=sys.stdin.buffer, xml_out=sys.stdout.buffer,
                    batch_size=10):
    '''Annotates a <teiCorpus/>, consisting of multiple documents.'''
    contexts = []
    with ET.xmlfile(xml_out, encoding='utf-8') as xf:
        batch = []
        def annotate_batch():
            nonlocal batch, xf
            if len(batch) > 0:
                annotate_tei_docs(nlp, batch)
                for doc in batch:
                    xf.write(doc)
                    doc.clear()
                batch = []
        for event, element in zdl.tei.parse_corpus(xml_in):
            if event == 'end' and element.tag == zdl.tei.TEI_TAG:
                if len(batch) == batch_size:
                    annotate_batch()
                batch.append(element)
            elif event == 'end' and element.tag == zdl.tei.TEI_HEADER_TAG:
                xf.write(element)
                element.clear()
            elif element.tag == zdl.tei.TEI_CORPUS_TAG:
                if event == 'start':
                    element_ctx = xf.element(
                        element.tag,
                        element.attrib,
                        element.nsmap
                    )
                    element_ctx.__enter__()
                    contexts.append(element_ctx)
                elif event == 'end':
                    annotate_batch()
                    contexts.pop().__exit__(None, None, None)
                    element.clear()
        annotate_batch()

def main():
    '''Command line interface.'''
    nlp = zdl.spacy.pipe.create(
        model_type='dist', ner=True, korap=False,
        dwdsmor_path='resources/dwdsmor/dwdsmor.ca'
    )
    annotate_corpus(nlp, batch_size=1)

if __name__ == '__main__':
    main()
