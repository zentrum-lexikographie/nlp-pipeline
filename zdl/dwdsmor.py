import csv
import itertools
import pathlib
import re
import sys

from collections import namedtuple
from functools import cached_property

import click
import sfst_transduce

import zdl.resources

Component = namedtuple("Component", ["form", "lemma", "tags"])


class Analysis(tuple):
    def __new__(cls, analysis, components):
        inst = tuple.__new__(cls, components)
        inst.analysis = analysis
        return inst

    @cached_property
    def form(self):
        return "".join(a.form for a in self)

    @cached_property
    def lemma(self):
        return "".join(a.lemma for a in self)

    @cached_property
    def segmented_lemma(self):
        return re.sub(r"(<IDX[^>]+>)?(<PAR[^>]+>)?(<\^ABBR>)?<\+[^>]+>.*", "", self.analysis)

    @cached_property
    def tags(self):
        return [tag for a in self for tag in a.tags]

    @cached_property
    def lemma_index(self):
        for tag in self.tags:
            if tag.startswith("IDX"):
                return tag[3:]

    @cached_property
    def paradigm_index(self):
        for tag in self.tags:
            if tag.startswith("PAR"):
                return tag[3:]

    @cached_property
    def pos(self):
        for tag in self.tags:
            if tag.startswith("+"):
                return tag[1:]

    _subcat_tags       = {"Pers": True, "Refl": True, "Def": True, "Indef": True, "Neg": True}
    _auxiliary_tags    = {"haben": True, "sein": True}
    _degree_tags       = {"Pos": True, "Comp": True, "Sup": True}
    _person_tags       = {"1": True, "2": True, "3": True}
    _gender_tags       = {"Fem": True, "Neut": True, "Masc": True, "NoGend": True, "Invar": True}
    _case_tags         = {"Nom": True, "Gen": True, "Dat": True, "Acc": True, "Invar": True}
    _number_tags       = {"Sg": True, "Pl": True, "Invar": True}
    _inflection_tags   = {"St": True, "Wk": True, "NoInfl": True, "Invar": True}
    _function_tags     = {"Attr": True, "Subst": True, "Pred": True, "Adv": True}
    _nonfinite_tags    = {"Inf": True, "PPres": True, "PPast": True}
    _mood_tags         = {"Ind": True, "Subj": True, "Imp": True}
    _tense_tags        = {"Pres": True, "Past": True}
    _abbreviation_tags = {"^ABBR": True}
    _metainfo_tags     = {"Old": True, "NonSt": True, "CAP": True}

    def tag_of_type(self, type_map):
        for tag in self.tags:
            if tag in type_map:
                return tag

    @cached_property
    def subcat(self):
        return self.tag_of_type(Analysis._subcat_tags)

    @cached_property
    def auxiliary(self):
        return self.tag_of_type(Analysis._auxiliary_tags)

    @cached_property
    def degree(self):
        return self.tag_of_type(Analysis._degree_tags)

    @cached_property
    def person(self):
        return self.tag_of_type(Analysis._person_tags)

    @cached_property
    def gender(self):
        return self.tag_of_type(Analysis._gender_tags)

    @cached_property
    def case(self):
        return self.tag_of_type(Analysis._case_tags)

    @cached_property
    def number(self):
        return self.tag_of_type(Analysis._number_tags)

    @cached_property
    def inflection(self):
        return self.tag_of_type(Analysis._inflection_tags)

    @cached_property
    def function(self):
        return self.tag_of_type(Analysis._function_tags)

    @cached_property
    def nonfinite(self):
        return self.tag_of_type(Analysis._nonfinite_tags)

    @cached_property
    def mood(self):
        return self.tag_of_type(Analysis._mood_tags)

    @cached_property
    def tense(self):
        return self.tag_of_type(Analysis._tense_tags)

    @cached_property
    def abbreviation(self):
        if self.tag_of_type(Analysis._abbreviation_tags):
            return "yes"

    @cached_property
    def metainfo(self):
        return self.tag_of_type(Analysis._metainfo_tags)

    def as_dict(self):
        return {"form": self.form,
                "analysis": self.analysis,
                "lemma": self.lemma,
                "segmentedlemma": self.segmented_lemma,
                "lemma_index": self.lemma_index,
                "paradigm_index": self.paradigm_index,
                "pos": self.pos,
                "subcat": self.subcat,
                "auxiliary": self.auxiliary,
                "degree": self.degree,
                "person": self.person,
                "gender": self.gender,
                "case": self.case,
                "number": self.number,
                "inflection": self.inflection,
                "function": self.function,
                "nonfinite": self.nonfinite,
                "mood": self.mood,
                "tense": self.tense,
                "abbreviation": self.abbreviation,
                "metainfo": self.metainfo}

    _empty_component_texts = set(["", ":"])
    _curly_braces_re = re.compile(r"[{}]")
    _escaped_colon_re = re.compile(r"\\:")

    def _decode_component_text(text):
        lemma = ""
        form  = ""
        text_len = len(text)
        ti = 0
        prev = None
        while ti < text_len:
            current = text[ti]
            nti = ti + 1
            next = text[nti] if nti < text_len else None
            if current == ":":
                lemma += prev or ""
                form  += next or ""
                ti += 1
            elif next != ":":
                if current == "_":
                    lemma += ":"
                    form  += ":"
                else:
                    lemma += current
                    form  += current
            ti += 1
            prev = current
        return {"lemma": lemma, "form": form}

    def _decode_analysis(analysis):
        # "QR-Code" -> "{:<>QR}:<>-<TRUNC>:<>Code<+NN>:<><Masc>:<><Acc>:<><Sg>:<>"
        analysis = Analysis._curly_braces_re.sub("", analysis)
        # replace escaped component separator ":" with underscore; gets
        # reversed in _decode_component_text
        analysis = Analysis._escaped_colon_re.sub("_", analysis)
        for a in re.finditer(r"([^<]*)(?:<([^>]*)>)?", analysis):
            text = a.group(1)
            tag  = a.group(2) or ""
            component = Analysis._decode_component_text(text)
            if tag != "":
                component["tag"] = tag
            yield component

    def _join_tags(components):
        result = []
        current = None
        for c in components:
            c = c.copy()
            if current is None or c["form"] != "" or c["lemma"] != "":
                c["tags"] = []
                result.append(c)
                current = c
            if "tag" in c:
                current["tags"].append(c["tag"])
                del c["tag"]
        return result

    def _join_untagged(components):
        result = []
        buf = []
        for c in components:
            buf.append(c)
            if len(c["tags"]) > 0:
                joined = {"lemma": "", "form": "", "tags": []}
                for c in buf:
                    joined["lemma"] += c["lemma"]
                    joined["form"]  += c["form"]
                    joined["tags"]  += c["tags"]
                result.append(joined)
                buf = []
        if len(buf) > 0:
            result = result + buf
        return result


def parse(analyses):
    for analysis in analyses:
        components = Analysis._decode_analysis(analysis)
        components = Analysis._join_tags(components)
        components = Analysis._join_untagged(components)
        yield Analysis(analysis, [Component(**c) for c in components])


def analyse_word(transducer, word):
    return parse(transducer.analyse(word))


def analyse_words(transducer, words):
    return tuple(analyse_word(transducer, word) for word in words)


def translate_input(records):
    words = []
    for record in records:
        if len(record) == 1:
            continue
        elif len(record) == 0:
            if len(words) > 0:
                yield words
                words = []
        elif len(record) == 2:
            words.append(record[0])
    if len(words) > 0:
        yield words


def partition_all(n, iterable):
    it = iter(iterable)
    while True:
        chunk = list(itertools.islice(it, n))
        if not chunk:
            return
        yield chunk


resources = zdl.resources.bucket('dwdsmor')
default_model = (pathlib.Path(resources) / 'smor-full.ca').as_posix()


@click.command()
@click.option('-m', '--model', default=default_model)
@click.option('-b', '--batch', default=1000)
def main(model, batch):
    transducer = sfst_transduce.CompactTransducer(model)
    transducer.both_layers = True

    input = csv.reader(sys.stdin)
    output = csv.writer(sys.stdout)

    sentences = translate_input(input)
    batches = partition_all(batch, sentences)
    for batch in batches:
        for sentence in batch:
            for token in sentence:
                analyses = []
                for analysis in parse(transducer.analyse(token)):
                    analysis = [
                        analysis.analysis,
                        analysis.lemma,
                        analysis.pos,
                        analysis.gender,
                        analysis.case,
                        analysis.number,
                        analysis.tense,
                        analysis.person
                    ]
                    if analysis not in analyses:
                        analyses.append(analysis)
                if len(analyses) > 0:
                    record = [
                        comp
                        for analysis in itertools.islice(analyses, 0, 100)
                        for comp in analysis
                    ]
                else:
                    record = [token, None, None, None, None, None, None, None]
                output.writerow(record)
            output.writerow([])
        sys.stdout.flush()


if __name__ == "__main__":
    main()
