"""Processing of TEI-P5/XML sources."""
import csv
import re
from collections import namedtuple
from itertools import repeat
from pathlib import Path

import lxml.etree as ET

from zdl.log import logger

def tei_tag(tag):
    """Qualifies a tag name with the TEI namespace."""
    return '{http://www.tei-c.org/ns/1.0}' + tag

_tag_categories = {}
_tag_categories_file = (Path(__file__) / '..' / 'corpus-schema.csv').resolve()

with _tag_categories_file.open() as _f:
    for _tag, _category in csv.reader(_f):
        _tag_categories[tei_tag(_tag)] = _category

def tei_tag_set(*tags):
    """Creates a set of tags in the TEI namespace."""
    return {tei_tag(tag) for tag in tags}

TEI_CORPUS_TAGS = tei_tag_set('teiCorpus', 'teiHeader', 'TEI')
TEI_CORPUS_TAG = tei_tag('teiCorpus')
TEI_HEADER_TAG = tei_tag('teiHeader')
TEI_TAG = tei_tag('TEI')

def parse_corpus(tei_file):
    """Parses a TEI file, yielding events for corpus, corpus header and
    text elements."""
    #pylint: disable=c-extension-no-member
    events = ET.iterparse(
        tei_file,
        events=['start', 'end', 'start-ns', 'end-ns'],
        strip_cdata=False,
        remove_comments=False,
        remove_pis=False
    )
    in_tei = False
    for event, element in events:
        if event == 'start' and element.tag in TEI_CORPUS_TAGS:
            if not in_tei:
                yield (event, element)
            if element.tag == TEI_TAG:
                in_tei = True
        elif event == 'end' and element.tag in TEI_CORPUS_TAGS:
            if in_tei and element.tag == TEI_TAG:
                in_tei = False
            if not in_tei:
                yield (event, element)

TEI_CHUNK_TAGS = tei_tag_set('ab', 'head', 'p')

def _is_nested_chunk(element):
    parent = element.getparent()
    while parent is not None:
        if parent.tag in TEI_CHUNK_TAGS:
            return True
        parent = parent.getparent()
    return False

TEI_BODY_TAG = tei_tag('body')

def iter_chunks(tei_element):
    """Iterates through all chunk-level elements of a tree."""
    for text_body in tei_element.iter(TEI_BODY_TAG):
        for element in text_body.iter():
            if element.tag in TEI_CHUNK_TAGS and not _is_nested_chunk(element):
                yield element

def _has_content(text):
    """The given text is not empty."""
    return text is not None and len(text) > 0

TEI_WS_TAGS = tei_tag_set('cb', 'lb', 'pb')

def _is_ws_element(element):
    return element.tag in TEI_WS_TAGS and\
        'no' != element.attrib.get('break', '')

_ws_run = re.compile(r'\s+')

def _normalize_space(text, parent_element):
    tag_category = _tag_categories.get(parent_element.tag, None)
    if tag_category == 'container':
        return _ws_run.sub('', text)
    if tag_category == 'content':
        return _ws_run.sub(' ', text)
    return text

def normalize_space(root):
    """Normalizes whitespace in given TEI element tree."""
    for element in root.iter():
        if _has_content(element.text):
            element.text = _normalize_space(element.text, element)
            # leading whitespace in chunk-level elements
            if element.tag in TEI_CHUNK_TAGS:
                element.text = element.text.lstrip()
        if _has_content(element.tail):
            parent = element.getparent()
            if parent is not None:
                element.tail = _normalize_space(element.tail, parent)
                # trailing whitespace in chunk-level elements
                if element.getnext() is None and\
                   parent.tag in TEI_CHUNK_TAGS:
                    element.tail = element.tail.rstrip()
        if _is_ws_element(element):
            # strip whitespace after whitespace element
            if _has_content(element.tail):
                element.tail = element.tail.lstrip()
            # strip whitespace before whitespace element
            # a) in tail of previous sibling
            prev = element.getprevious()
            if prev is not None:
                if _has_content(prev.tail):
                    prev.tail = prev.tail.rstrip()
            # or b) in text of parent (ws element being the first child)
            else:
                parent = element.getparent()
                if parent is not None and _has_content(parent.text):
                    parent.text = parent.text.rstrip()
    return root

def _add_segment(segments, segment):
    segment = _ws_run.sub(' ', segment)
    if len(segments) == 0 or segments[-1][-1] == ' ':
        segment = segment.lstrip()
    if len(segment) > 0:
        segments.append(segment)
    return segment

TEI_MS_TAGS = tei_tag_set('anchor', 'cb', 'lb', 'pb')

def _extract_text(element, segments, milestones, offset):
    if element.tag in TEI_MS_TAGS:
        milestones.append((offset, element.tag, element.attrib.items()))
        segment = _add_segment(segments, ' ')
        offset += len(segment)
    if _has_content(element.text):
        segment = _add_segment(segments, element.text)
        offset += len(segment)
    for child in element:
        offset = _extract_text(child, segments, milestones, offset)
    if _has_content(element.tail):
        segment = _add_segment(segments, element.tail)
        offset += len(segment)
    return offset

Text = namedtuple('Text', ['content', 'milestones'])

def extract_text(root):
    """Extracts plain text and milestone elements from given tree."""
    segments = []
    milestones = []
    _extract_text(root, segments, milestones, 0)
    return Text(''.join(segments).rstrip(), milestones)

def clear_children(root):
    '''Removes content of the given element.'''
    root.text = None
    for child in list(root.getchildren()):
        root.remove(child)

S_TAG = tei_tag('s')
W_TAG = tei_tag('w')
SPAN_TAG = tei_tag('span')

def _serialize_morph_attrs(word_element, morph, xml_attr, morph_attr):
    morph_vals = morph.get(morph_attr)
    if len(morph_vals) > 0:
        word_element.attrib[xml_attr] = ','.join(v.lower() for v in morph_vals)

def serialize_annotations(chunk, text, dep_doc, ner_doc):
    '''Serializes spaCy NLP annotations to TEI-P5/XML.'''
    clear_children(chunk)
    milestones = list(text.milestones)
    def serialize_milestones(parent, token_idx):
        nonlocal milestones
        while len(milestones) > 0:
            next_ms, next_ms_tag, next_ms_attrib = milestones[0]
            if next_ms <= token_idx:
                ET.SubElement(parent, next_ms_tag, dict(next_ms_attrib))
                milestones.pop(0)
            else:
                break
    dep_sents = dep_doc.sents
    ner_sents = ner_doc.sents if ner_doc is not None else repeat(None)
    for dep_sentence, ner_sentence in zip(dep_sents, ner_sents):
        sent_element = ET.SubElement(chunk, S_TAG)
        sent_offset = (dep_sentence[0].i - 1) if len(dep_sentence) > 0 else -1
        for word in dep_sentence:
            serialize_milestones(sent_element, word.idx)
            word_element = ET.SubElement(sent_element, W_TAG,{
                'n': str(word.i - sent_offset),
                'lemma': word._.dwdsmor_lemma or word.lemma_,
                'pos': word.tag_.lower()
            })
            if word._.dwdsmor_lemma is not None:
                if word._.dwdsmor_lemma != word.lemma_:
                    logger.info(
                        '%-30s | %-30s | %-30s',
                        word.text, word._.dwdsmor_lemma, word.lemma_
                    )
            word_element.text = word.text_with_ws
            if word.dep_ != 'ROOT':
                word_element.attrib['dep'] = word.dep_.lower()
                word_element.attrib['head'] = str(word.head.i - sent_offset)
            _serialize_morph_attrs(word_element, word.morph, 'gender', 'Gender')
            _serialize_morph_attrs(word_element, word.morph, 'case', 'Case')
            _serialize_morph_attrs(word_element, word.morph, 'number', 'Number')
            _serialize_morph_attrs(word_element, word.morph, 'tense', 'Tense')
            _serialize_morph_attrs(word_element, word.morph, 'person', 'Person')
            _serialize_morph_attrs(word_element, word.morph, 'mood', 'Mood')
            _serialize_morph_attrs(word_element, word.morph, 'degree', 'Degree')
        for rel, token_ids in dep_sentence._.dwds_colloc:
            ET.SubElement(sent_element, SPAN_TAG, {
                'type': 'collocation',
                'subtype': rel.lower(),
                'target': ' '.join(str(t - sent_offset) for t in token_ids)
            })
        if ner_sentence is not None:
            for entity in ner_sentence.ents:
                ET.SubElement(sent_element, SPAN_TAG, {
                    'type': 'entity',
                    'subtype': entity.label_.lower(),
                    'from': str(entity.start - sent_offset),
                    'to': str(entity.end - sent_offset - 1)
                })
    serialize_milestones(chunk, len(text.content))
