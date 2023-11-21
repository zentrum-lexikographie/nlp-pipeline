'''Collocation extraction.'''
from functools import partial

import spacy.language
import spacy.matcher

from zdl.log import logger

def _always_true(doc, token_ids):
    return True

_determiners = {"des", "der", "eines", "einer"}

def _is_gmod(doc, token_ids):
    nmod = doc[token_ids[0]]
    if any(det.text in _determiners for det in nmod.children):
        return not any(c.dep_ == 'case' for c in nmod.children)
    return False

def _is_subja(doc, token_ids):
    nsubj = doc[token_ids[1]]
    return not any(c.dep_ == 'cop' for c in nsubj.children)

def _is_pred(doc, token_ids):
    subj = doc[token_ids[0]]
    pred = doc[token_ids[1]]
    pred_rel = pred.dep_
    if pred_rel == 'nsubj':
        return any(c.dep_ == 'cop' and c.pos_ == 'AUX' for c in subj.children)\
            and not any(c.dep_ == 'case' for c in subj.children)
    elif pred_rel in  {'obl', 'obj'}:
        return any(c.text in {'als', 'für'} for c in subj.children)
    return False

def _is_kom(doc, token_ids):
    cconj = doc[token_ids[0]]
    return cconj == 'wie'

_patterns = {
    'ADV':   ([[['ADJ', 'ADV'], ['advmod'], ['VERB', 'ADJ']]], _always_true),
    'ATTR':  ([[['ADJ'], ['amod'], ['NOUN']]], _always_true),
    'GMOD':  ([[['NOUN'], ['nmod'], ['NOUN']]], _is_gmod),
    'KOM':   ([[['CCONJ'], ['case'], ['NOUN'], ['nmod', 'obl'],
                ['VERB', 'ADJ', 'NOUN']]], _is_kom),
    'KON':   ([[['CCONJ'], ['cc'], ['AUX', 'VERB', 'ADJ']]], _always_true),
    'OBJ':   ([[['NOUN'], ['iobj', 'obj'], ['VERB']]], _always_true),
    'PP':    ([[['ADP'], ['case'], ['NOUN'], ['nmod'], ['NOUN']],
               [['ADP'], ['case'], ['ADJ', 'ADV', 'NOUN'], ['obl'], ['VERB']]],
              _always_true),
    'PRED':  ([[['VERB'], ['obj', 'obl'], ['NOUN', 'VERB', 'ADJ']],
               [['VERB', 'ADJ', 'NOUN'], ['nsubj'], ['NOUN']]],
              _is_pred),
    'SUBJA': ([[['NOUN'], ['nsubj'], ['VERB', 'ADJ', 'NOUN']]], _is_subja),
    'SUBJP': ([[['NOUN'], ['nsubj:pass'], ['VERB']]], _always_true),
    'VZ':    ([[['ADP'], ['compound:prt'], ['AUX', 'VERB', 'ADJ']]],
              _always_true)
}


def _on_match(vocab, predicate, matcher, doc, i,  matches):
    if i != 0:
        return
    for match_id, token_ids in matches:
        if predicate(doc, token_ids):
            rel =  vocab.strings[match_id]
            doc[token_ids[0]].sent._.dwds_colloc.append((rel, token_ids))

def _dependency_matcher(vocab):
    matcher = spacy.matcher.DependencyMatcher(vocab, validate=True)
    for k, (paths, predicate) in _patterns.items():
        matcher_patterns = []
        for path in paths:
            if len(path) == 3:
                left, dep, right = path
                for l in left:
                    for d in dep:
                        for r in right:
                            matcher_patterns.append([{
                                'RIGHT_ID': 'a0',
                                'RIGHT_ATTRS': {'POS': l, 'DEP': d}
                            }, {
                                'REL_OP': '<',
                                'LEFT_ID': 'a0',
                                'RIGHT_ID': 'a1',
                                'RIGHT_ATTRS': {'POS': r}
                            }])
            elif len(path) == 5:
                left, ldep, middle, rdep, right = path
                for l in left:
                    for ld in ldep:
                        for m in middle:
                            for rd in rdep:
                                for r in right:
                                    matcher_patterns.append([{
                                        'RIGHT_ID': 'a0',
                                        'RIGHT_ATTRS': {'POS': l, 'DEP': ld}
                                    }, {
                                        'REL_OP': '<',
                                        'LEFT_ID': 'a0',
                                        'RIGHT_ID': 'a1',
                                        'RIGHT_ATTRS': {'POS': m, 'DEP': rd}
                                    }, {
                                        'REL_OP': '<',
                                        'LEFT_ID': 'a1',
                                        'RIGHT_ID': 'a2',
                                        'RIGHT_ATTRS': {'POS': r}
                                    }])
        if len(matcher_patterns) > 0:
            on_match = partial(_on_match, vocab, predicate)
            matcher.add(k, matcher_patterns, on_match=on_match)
    return matcher

def _annotate(matcher, doc):
    matcher(doc)
    return doc

#pylint: disable=unused-argument
@spacy.language.Language.factory('dwds_colloc')
def component(nlp, name):
    if not spacy.tokens.Span.has_extension('dwds_colloc'):
        spacy.tokens.Span.set_extension('dwds_colloc', default=[])
    return partial(_annotate, _dependency_matcher(nlp.vocab))
