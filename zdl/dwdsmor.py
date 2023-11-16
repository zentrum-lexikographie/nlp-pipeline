'''Processing of morphological analyses from DWDSmor'''
import re
import sys
from collections import namedtuple
from functools import cached_property, partial

#pylint: disable=no-name-in-module
from sfst_transduce import CompactTransducer

import spacy
import spacy.language
import spacy.tokens

_subcat_tags       = {'Pers', 'Refl', 'Def', 'Indef', 'Neg'}
_auxiliary_tags    = {'haben', 'sein'}
_degree_tags       = {'Pos', 'Comp', 'Sup'}
_person_tags       = {'1', '2', '3'}
_gender_tags       = {'Fem', 'Neut', 'Masc', 'NoGend', 'Invar'}
_case_tags         = {'Nom', 'Gen', 'Dat', 'Acc', 'Invar'}
_number_tags       = {'Sg', 'Pl', 'Invar'}
_inflection_tags   = {'St', 'Wk', 'NoInfl', 'Invar'}
_function_tags     = {'Attr', 'Subst', 'Pred', 'Adv'}
_nonfinite_tags    = {'Inf', 'PPres', 'PPast'}
_mood_tags         = {'Ind', 'Subj', 'Imp'}
_tense_tags        = {'Pres', 'Past'}
_abbreviation_tags = {'^ABBR'}
_metainfo_tags     = {'Old', 'NonSt', 'CAP'}

def _tag_of_type(tags, type_map):
    for tag in tags:
        if tag in type_map:
            return tag
    return None

Component = namedtuple('Component', ['form', 'lemma', 'tags'])

_segmented_lemma_re = re.compile(
    r'(<IDX[^>]+>)?(<PAR[^>]+>)?(<\^ABBR>)?<\+[^>]+>.*'
)

_smor_to_stts_number = {'Sg': 'Sing', 'Pl': 'Plur'}
_smor_to_stts_degree = {'Comp': 'Cmp'}

def _stts_tag(smor_tag, mapping):
    if smor_tag is not None:
        return mapping.get(smor_tag, smor_tag)
    return None

#pylint: disable=too-many-public-methods,missing-function-docstring
class Analysis(tuple):
    '''Wraps a parsed morphological analysis, providing access to its
    components.'''
    def __new__(cls, analysis, components):
        inst = tuple.__new__(cls, components)
        inst.text = analysis
        return inst

    @cached_property
    def form(self):
        return ''.join(a.form for a in self)

    @cached_property
    def lemma(self):
        return ''.join(a.lemma for a in self)

    @cached_property
    def segmented_lemma(self):
        return _segmented_lemma_re.sub('', self.text)

    @cached_property
    def tags(self):
        return [tag for a in self for tag in a.tags]

    @cached_property
    def lemma_index(self):
        for tag in self.tags:
            if tag.startswith('IDX'):
                return tag[3:]
        return None

    @cached_property
    def paradigm_index(self):
        for tag in self.tags:
            if tag.startswith('PAR'):
                return tag[3:]
        return None

    @cached_property
    def pos(self):
        for tag in self.tags:
            if tag.startswith('+'):
                return tag[1:]
        return None

    @cached_property
    def subcat(self):
        return _tag_of_type(self.tags, _subcat_tags)

    @cached_property
    def auxiliary(self):
        return _tag_of_type(self.tags, _auxiliary_tags)

    @cached_property
    def degree(self):
        return _tag_of_type(self.tags, _degree_tags)

    @cached_property
    def stts_degree(self):
        return _stts_tag(self.degree, _smor_to_stts_degree)

    @cached_property
    def person(self):
        return _tag_of_type(self.tags, _person_tags)

    @cached_property
    def gender(self):
        return _tag_of_type(self.tags, _gender_tags)

    @cached_property
    def case(self):
        return _tag_of_type(self.tags, _case_tags)

    @cached_property
    def number(self):
        return _tag_of_type(self.tags, _number_tags)

    @cached_property
    def stts_number(self):
        return _stts_tag(self.number, _smor_to_stts_number)

    @cached_property
    def inflection(self):
        return _tag_of_type(self.tags, _inflection_tags)

    @cached_property
    def function(self):
        return _tag_of_type(self.tags, _function_tags)

    @cached_property
    def nonfinite(self):
        return _tag_of_type(self.tags, _nonfinite_tags)

    @cached_property
    def mood(self):
        return _tag_of_type(self.tags, _mood_tags)

    @cached_property
    def tense(self):
        return _tag_of_type(self.tags, _tense_tags)

    @cached_property
    def abbreviation(self):
        if _tag_of_type(self.tags, _abbreviation_tags):
            return 'yes'
        return None

    @cached_property
    def metainfo(self):
        return _tag_of_type(self.tags, _metainfo_tags)

    def as_dict(self):
        return {'form': self.form,
                'analysis': self.text,
                'lemma': self.lemma,
                'segmentedlemma': self.segmented_lemma,
                'lemma_index': self.lemma_index,
                'paradigm_index': self.paradigm_index,
                'pos': self.pos,
                'subcat': self.subcat,
                'auxiliary': self.auxiliary,
                'degree': self.degree,
                'person': self.person,
                'gender': self.gender,
                'case': self.case,
                'number': self.number,
                'inflection': self.inflection,
                'function': self.function,
                'nonfinite': self.nonfinite,
                'mood': self.mood,
                'tense': self.tense,
                'abbreviation': self.abbreviation,
                'metainfo': self.metainfo}

_empty_component_texts = set(['', ':'])
_curly_braces_re = re.compile(r'[{}]')
_escaped_colon_re = re.compile(r'\\:')

def _decode_component_text(text):
    lemma = ''
    form  = ''
    text_len = len(text)
    index = 0
    prev_char = None
    while index < text_len:
        current_char = text[index]
        next_index = index + 1
        next_char = text[next_index] if next_index < text_len else None
        if current_char == ':':
            lemma += prev_char or ''
            form  += next_char or ''
            index += 1
        elif next_char != ':':
            if current_char == '_':
                lemma += ':'
                form  += ':'
            else:
                lemma += current_char
                form  += current_char
        index += 1
        prev_char = current_char
    return (form, lemma)

_components_re = re.compile(r'([^<]*)(?:<([^>]*)>)?')

def _decode_analysis(analysis):
    # 'QR-Code' -> '{:<>QR}:<>-<TRUNC>:<>Code<+NN>:<><Masc>:<><Acc>:<><Sg>:<>'
    analysis = _curly_braces_re.sub('', analysis)

    # replace escaped component separator ':' with underscore; gets
    # reversed in _decode_component_text
    analysis = _escaped_colon_re.sub('_', analysis)

    for component in _components_re.finditer(analysis):
        text = component.group(1)
        form, lemma = _decode_component_text(text)
        tag  = component.group(2) or ''
        tags = [tag] if tag != '' else []
        yield Component(form, lemma, tags)

def _join_tags(components):
    result = []
    form = ''
    lemma = ''
    tags = []
    for component in components:
        if component.form == '' and component.lemma == '':
            tags.extend(component.tags)
            continue
        result.append(Component(form, lemma, tags))
        form = component.form
        lemma = component.lemma
        tags = component.tags
    if form != '' or lemma != '':
        result.append(Component(form, lemma, tags))
    return result

def _join_untagged(components):
    result = []
    buf = []
    for c in components:
        buf.append(c)
        if len(c.tags) > 0:
            form = ''
            lemma = ''
            tags = []
            for c in buf:
                form  += c.form
                lemma += c.lemma
                tags.extend(c.tags)
            result.append(Component(form, lemma, tags))
            buf = []
    if len(buf) > 0:
        result.extend(buf)
    return result

def parse(analyses):
    for analysis in analyses:
        components = _decode_analysis(analysis)
        components = _join_tags(components)
        components = _join_untagged(components)
        yield Analysis(analysis, components)


def analyse_word(transducer, word):
    return parse(transducer.analyse(word))


def analyse_words(transducer, words):
    return tuple(analyse_word(transducer, word) for word in words)

def create_transducer(transducer_path):
    transducer = CompactTransducer(transducer_path)
    transducer.both_layers = True
    return transducer

_stts_to_smor_pos = {
    'ADJA': {'ADJ', 'ORD', 'INDEF', 'CARD'},
    'ADJD': {'ADJ'},
    'APPO': {'POSTP'},
    'APPR': {'PREP'},
    'APPRART': {'PREPART'},
    'APZR': {'PREP', 'POSTP'},
    'ITJ': {'INTJ'},
    'KOKOM': {'CONJ'},
    'KON': {'CONJ'},
    'KOUI': {'CONJ'},
    'KOUS': {'CONJ'},
    'NE': {'NPROP'},
    'NN': {'NPROP', 'NN'},
    'PDAT': {'DEM'},
    'PDS': {'DEM'},
    'PIAT': {'INDEF'},
    'PIDAT': {'INDEF'},
    'PIS': {'INDEF'},
    'PPER': {'PPRO'},
    'PPOSAT': {'POSS'},
    'PPOSS': {'POSS'},
    'PRELAT': {'REL'},
    'PRELS': {'REL'},
    'PRF': {'PPRO'},
    'PROP': {'ADV', 'PROADV'},
    'PTKA': {'PTCL'},
    'PTKANT': {'PTCL', 'INTJ'},
    'PTKNEG': {'PTCL'},
    'PTKVZ': {'VPART', 'ADV', 'PREP'},
    'PTKZU': {'PTCL'},
    'PWAT': {'WPRO'},
    'PWAV': {'ADV'},
    'PWS': {'WPRO'},
    'VAFIN': {'V'},
    'VAIMP': {'V'},
    'VAINF': {'V'},
    'VAPP': {'V'},
    'VMFIN': {'V'},
    'VMINF': {'V'},
    'VMPP': {'V'},
    'VVFIN': {'V'},
    'VVIMP': {'V'},
    'VVINF': {'V'},
    'VVIZU': {'V'},
    'VVPP': {'V'}
}

def score_analysis(v, morph, attr, score):
    if v is not None:
        for morph_v in morph.get(attr):
            if v == morph_v:
                return score
    return 0

def rank_analysis(analysis, token):
    rank = 0
    rank += score_analysis(analysis.gender, token.morph, 'Gender', 1)
    rank += score_analysis(analysis.case, token.morph, 'Case', 1)
    rank += score_analysis(analysis.stts_number, token.morph, 'Number', 1)
    rank += score_analysis(analysis.tense, token.morph, 'Tense', 1)
    rank += score_analysis(analysis.person, token.morph, 'Person', 1)
    rank += score_analysis(analysis.mood, token.morph, 'Mood', 1)
    rank += score_analysis(analysis.stts_degree, token.morph, 'Degree', 1)
    return rank

#pylint: disable=unused-argument
def annotate_doc(nlp, transducer, doc):
    for token in doc:
        pos_set = _stts_to_smor_pos.get(token.tag_)
        if pos_set is not None:
            ranking = []
            for analysis in analyse_word(transducer, token.text):
                if analysis.pos in pos_set:
                    ranking.append((rank_analysis(analysis, token), analysis))
            if len(ranking) > 0:
                ranking.sort(key=(lambda v: -v[0]))
                token._.dwdsmor_lemma = ranking[0][1].lemma
    return doc

#pylint: disable=unused-argument
@spacy.language.Language.factory(
    'dwdsmor', default_config={'transducer_path': 'dwdsmor.ca'}
)
def create_component(nlp, name, transducer_path=None):
    if not spacy.tokens.Token.has_extension('dwds_morph'):
        spacy.tokens.Token.set_extension('dwdsmor_lemma', default=None)
    return partial(annotate_doc, nlp, create_transducer(transducer_path))

def main():
    transducer = create_transducer('resources/dwdsmor/dwdsmor.ca')
    for analyses in analyse_words(transducer, sys.argv[1:]):
        for analysis in analyses:
            print(repr(
                (analysis.text, analysis.form, analysis.lemma, analysis.pos)
            ))
        print()

if __name__ == '__main__':
    main()
