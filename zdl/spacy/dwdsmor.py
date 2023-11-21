'''DWDSmor component for spaCy.'''
from functools import partial

import spacy.language
import spacy.tokens
import zdl.dwdsmor

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

def _score(v, morph, attr, score):
    if v is not None:
        for morph_v in morph.get(attr):
            if v == morph_v:
                return score
    return 0

def _rank(analysis, token):
    rank = 0
    rank += _score(analysis.gender, token.morph, 'Gender', 1)
    rank += _score(analysis.case, token.morph, 'Case', 1)
    rank += _score(analysis.stts_number, token.morph, 'Number', 1)
    rank += _score(analysis.tense, token.morph, 'Tense', 1)
    rank += _score(analysis.person, token.morph, 'Person', 1)
    rank += _score(analysis.mood, token.morph, 'Mood', 1)
    rank += _score(analysis.stts_degree, token.morph, 'Degree', 1)
    return rank

#pylint: disable=unused-argument
def _annotate(nlp, transducer, doc):
    for token in doc:
        pos_set = _stts_to_smor_pos.get(token.tag_)
        if pos_set is not None:
            ranking = []
            for analysis in zdl.dwdsmor.analyse_word(transducer, token.text):
                if analysis.pos in pos_set:
                    ranking.append((_rank(analysis, token), analysis))
            if len(ranking) > 0:
                ranking.sort(key=(lambda v: -v[0]))
                token._.dwdsmor_lemma = ranking[0][1].lemma
    return doc

#pylint: disable=unused-argument
@spacy.language.Language.factory(
    'dwdsmor', default_config={'transducer_path': 'dwdsmor.ca'}
)
def component(nlp, name, transducer_path=None):
    if not spacy.tokens.Token.has_extension('dwdsmor_lemma'):
        spacy.tokens.Token.set_extension('dwdsmor_lemma', default=None)
    return partial(
        _annotate, nlp, zdl.dwdsmor.create_transducer(transducer_path)
    )
