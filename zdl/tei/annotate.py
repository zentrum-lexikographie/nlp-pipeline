"""Annotation pipeline."""
from collections import namedtuple
from itertools import tee
import multiprocessing
import sys

import lxml.etree as ET

import zdl.spacy.pipe
import zdl.tei.util
from zdl.log import logger

TEIXMLEvent = namedtuple('TEIXMLEvent', ['n', 'event', 'element', 'chunk'])

def _parse_xml_input(xml_input):
    for n, (event, element) in enumerate(zdl.tei.util.parse_corpus(xml_input)):
        if event == 'end' and element.tag == zdl.tei.util.TEI_TAG:
            for chunk in zdl.tei.util.iter_chunks(element):
                yield TEIXMLEvent(n, event, element, chunk)
            yield TEIXMLEvent(n, event, element, None)
        else:
            yield TEIXMLEvent(n, event, element, None)

def _tag_chunks(nlp, events):
    events = (e for e in events if e.chunk is not None)
    events, texts = tee(events)
    texts = (zdl.tei.util.extract_text(e.chunk) for e in texts)
    texts, contents = tee(texts)
    docs = nlp(t.content for t in contents)
    for event, text, doc in zip(events, texts, docs):
        zdl.tei.util.serialize_annotations(event.chunk, text, doc)
        yield event

def _annotated_xml_events(nlp, xml_input):
    events = _parse_xml_input(xml_input)
    events, chunks = tee(events)

    events = (e for e in events if e.chunk is None)
    chunks = _tag_chunks(nlp, chunks)

    event = next(events, None)
    chunk = next(chunks, None)
    while event is not None:
        while chunk is not None and chunk.n <= event.n:
            chunk = next(chunks, None)
        yield event
        event = next(events, None)

def _annotate_corpus(nlp, xml_in=sys.stdin.buffer, xml_out=sys.stdout.buffer):
    contexts = []
    with ET.xmlfile(xml_out, encoding='utf-8') as xf:
        for e in _annotated_xml_events(nlp, xml_in):
            _, event, element, _ = e
            if event == 'end' and element.tag == zdl.tei.util.TEI_TAG:
                xf.write(element)
                element.clear()
            elif event == 'end' and element.tag == zdl.tei.util.TEI_HEADER_TAG:
                xf.write(element)
                element.clear()
            elif element.tag == zdl.tei.util.TEI_CORPUS_TAG:
                if event == 'start':
                    element_ctx = xf.element(
                        element.tag,
                        element.attrib,
                        element.nsmap
                    )
                    element_ctx.__enter__()
                    contexts.append(element_ctx)
                elif event == 'end':
                    contexts.pop().__exit__(None, None, None)
                    element.clear()

def main():
    '''Command line interface.'''
    nlp = zdl.spacy.pipe.create(
        model_type='lg', dwdsmor_path='resources/dwdsmor/dwdsmor.ca'
    )
    _annotate_corpus(nlp)

if __name__ == '__main__':
    multiprocessing.set_start_method('spawn')
    main()
