import json
from collections import defaultdict
from itertools import chain

from .conllu import feat, form_text, lemma_text

relations = {
    "ADV": {
        "desc": "Adverbialbestimmung",
        "patterns": (
            ("advmod", "verb", "adv"),
            ("advmod", "adj", "adv"),
            ("advmod", "verb", "adj"),
            ("advmod", "adj", "adj"),
        ),
    },
    "ATTR": {"desc": "Adjektivattribut", "patterns": (("amod", "noun", "adj"),)},
    "GMOD": {"desc": "Genitivattribut", "tags": ("noun",)},
    "KOM": {"desc": "vergleichende Wortgruppe", "tags": ("adj", "noun", "verb")},
    "KON": {
        "desc": "Koordination",
        "patterns": (
            ("conj", "cc", "noun", "noun", "cconj"),
            ("conj", "cc", "verb", "verb", "cconj"),
            ("conj", "cc", "adj", "adj", "cconj"),
        ),
    },
    "OBJ": {"desc": "Akkusativ-Objekt", "tags": ("noun", "verb")},
    "OBJO": {"desc": "Dativ-/Genitiv-Objekt", "tags": ("noun", "verb")},
    "PP": {
        "desc": "Präpositionalgruppe",
        "patterns": (
            ("nmod", "case", "noun", "noun", "adp"),
            ("obl", "case", "verb", "noun", "adp"),
            ("obl", "case", "verb", "adj", "adp"),
            ("obl", "case", "verb", "adv", "adp"),
            ("obj", "case", "verb", "noun", "adp"),
        ),
    },
    "PREDC": {"desc": "Prädikativ", "tags": ("adj", "noun", "verb")},
    "PRED": {"desc": "Prädikativ", "tags": ("adj", "noun", "verb")},
    "SUBJA": {"desc": "Subjekt", "tags": ("adj", "noun", "verb")},
    "SUBJP": {"desc": "Passivsubjekt", "tags": ("noun", "verb")},
}


def tags_of_relation(r):
    tags = set(r.get("tags", tuple()))
    for pattern in r.get("patterns", tuple()):
        s_idx = 1 if len(pattern) == 3 else 2
        tags.update(pattern[s_idx : s_idx + 2])
    return tags


all_relation_types = frozenset(relations.keys())
all_tags = frozenset(
    (t.upper() for r in relations.values() for t in tags_of_relation(r))
)


pattern_index = defaultdict(lambda: defaultdict(str))  # type: ignore
for colloc, desc in relations.items():
    for p in desc.get("patterns", tuple()):  # type: ignore
        l_p = len(p)
        assert l_p == 3 or l_p == 5, "Pattern has unknown dimension"
        if len(p) == 3:
            r1, t1, t2 = p
            r_k = r1
            t_k = (t1.upper(), t2.upper())
        else:
            r1, r2, t1, t2, t3 = p
            r_k = (r1, r2)
            t_k = (t1.upper(), t2.upper(), t3.upper())  # type: ignore
        pattern_index[r_k][t_k] = colloc


def extract_by_patterns(s):
    for t_idx, t in enumerate(s):
        t_n = t_idx + 1
        t_head_1_n = int(t["head"])
        if t_head_1_n <= 0:
            # token is root
            continue
        t_head_1 = s[t_head_1_n - 1]
        t_deprel = t["deprel"]
        if pattern := pattern_index.get(t_deprel):
            if colloc := pattern[(t_head_1["upos"], t["upos"])]:
                yield (colloc, t_head_1_n, t_n)
        t_head_2_n = int(t_head_1["head"])
        if t_head_2_n <= 0:
            # token head is root, cannot make ternary relation
            continue
        if pattern := pattern_index.get((t_head_1["deprel"], t_deprel)):
            t_head_2 = s[t_head_2_n - 1]
            if colloc := pattern.get((t_head_2["upos"], t_head_1["upos"], t["upos"])):
                if colloc == "KON":
                    yield (colloc, t_head_2_n, t_head_1_n)
                else:
                    yield (colloc, t_head_2_n, t_head_1_n, t_n)


def extract_comparing_groups(s):
    for t in s:
        t_head_1_n = int(t["head"])
        if (
            t_head_1_n <= 0
            or t["deprel"] != "case"
            or t["upos"] != "CCONJ"
            or t["form"] not in ["als", "wie"]
        ):
            # token is root
            continue
        t_head_1 = s[t_head_1_n - 1]
        t_head_2_n = int(t_head_1["head"])
        if (
            t_head_2_n <= 0
            or t_head_1["deprel"] not in {"obl", "nmod"}
            or t_head_1["upos"] != "NOUN"
        ):
            # token head is root, cannot make ternary relation
            continue
        t_head_2 = s[int(t_head_2_n) - 1]
        if t["form"] == "als":
            # and t_head_2["upos"] != 'ADJ':
            # expect relations with 'als' to relate to an adjective
            # TODO: preferably check for comparative
            # (https://universaldependencies.org/u/feat/Degree.html)
            continue
        if t_head_2["upos"] in {"ADJ", "VERB", "NOUN"}:
            yield ("KOM", t_head_2_n, t_head_1_n)


def dependants(s, head):
    head_n = head["id"]
    return [t for t in s if t["head"] == head_n]


def has_case(token, case):
    return feat(token, "Case") == case


def extract_genitives(s):
    for t in s:
        if t["upos"] != "NOUN":
            continue
        t_deps_1 = dependants(s, t)
        for t_dep_1 in t_deps_1:
            if t_dep_1["deprel"] != "nmod" or t_dep_1["upos"] != "NOUN":
                continue
            t_deps_2 = dependants(s, t_dep_1)
            if not has_case(t_dep_1, "Gen") or not any(
                has_case(t_dep_2, "Gen") for t_dep_2 in t_deps_2
            ):
                continue
            if any(t_dep_2["deprel"] == "case" for t_dep_2 in t_deps_2):
                continue
            yield ("GMOD", t["id"], t_dep_1["id"])


def extract_active_subjects(s):
    for t in s:
        t_deps_1 = dependants(s, t)
        if any(t["deprel"] == "cop" for t in t_deps_1):
            continue
        if t["upos"] not in {"NOUN", "VERB", "ADJ"}:
            continue
        for t_dep_1 in t_deps_1:
            if t_dep_1["deprel"] == "nsubj" and t_dep_1["upos"] == "NOUN":
                yield ("SUBJA", t["id"], t_dep_1["id"])


def extract_passive_subjects(s):
    for t in s:
        if t["upos"] != "VERB":
            continue
        t_deps_1 = dependants(s, t)
        for t_dep_1 in t_deps_1:
            if t_dep_1["upos"] != "NOUN" or t_dep_1["deprel"] != "nsubj:pass":
                continue
            if any(
                t_dep_1a["deprel"] == "aux:pass" and t_dep_1a["lemma"] == "werden"
                for t_dep_1a in t_deps_1
            ):
                yield ("SUBJP", t["id"], t_dep_1["id"])


def extract_objects(s):
    for t in s:
        if t["upos"] != "VERB":
            continue
        t_deps_1 = dependants(s, t)
        for t_dep_1 in t_deps_1:
            if t_dep_1["deprel"] not in {"obj", "obl:arg"} or t_dep_1["upos"] != "NOUN":
                continue
            t_deps_2 = dependants(s, t_dep_1)
            if any(t_dep_2["deprel"] == "case" for t_dep_2 in t_deps_2):
                continue
            colloc = "OBJO"  # t_dep_1["deprel"] == "obl:arg"
            if t_dep_1["deprel"] == "obj":
                if not (
                    has_case(t_dep_1, "Dat")
                    or has_case(t_dep_1, "Gen")
                    or any(
                        t_dep_2["deprel"] != "nmod"
                        and (has_case(t_dep_2, "Gen") or has_case(t_dep_2, "Dat"))
                        for t_dep_2 in t_deps_2
                    )
                ) or has_case(t_dep_1, "Acc"):
                    colloc = "OBJ"
            yield (colloc, t["id"], t_dep_1["id"])


# List of verbs for object predicative relations, guided by
# information from E-VALBU on verbs with 'prd' complements:
#
# https://grammis.ids-mannheim.de/verbs/search?komplemente[]=praed&suchtabelle=lesart
#
_pred_verbs = {
    "für_adj-noun": {"befinden", "halten"},
    "als_adj": {
        "annehmen",
        "ansehen",
        "ausgeben",
        "befinden",
        "bezeichnen",
        "empfehlen",
        "erfahren",
        "erkennen",
        "erklären",
        "erleben",
        "erscheinen",
        "fürchten",
        "kennen",
        "kennenlernen",
        "kritisieren",
        "missverstehen",
        "nehmen",
        "rechnen",
        "verkaufen",
        "wirken",
        "zeichnen",
        "zählen",
    },
    "als_noun": {
        "ablehnen",
        "anfangen",
        "beginnen",
        "behalten",
        "behandeln",
        "benutzen",
        "bestimmen",
        "bestätigen",
        "bewerben",
        "buchstabieren",
        "dienen",
        "drucken",
        "eignen",
        "einsetzen",
        "enden",
        "entdecken",
        "erhalten",
        "finden",
        "funktionieren",
        "fühlen",
        "gebrauchen",
        "gehen",
        "gewinnen",
        "haben",
        "handeln",
        "kandidieren",
        "laufen",
        "lesen",
        "malen",
        "meinen",
        "nennen",
        "nutzen",
        "organisieren",
        "planen",
        "sehen",
        "tragen",
        "verbringen",
        "verstehen",
        "verwenden",
        "vorkommen",
        "vorschlagen",
        "vorstellen",
        "wirken",
        "wählen",
        "zeichnen",
        "zählen",
        "überraschen",
    },
    "wie_adj-noun": {
        "aussehen",
        "behandeln",
        "bleiben",
        "erscheinen",
        "fühlen",
        "gebrauchen",
        "malen",
        "organisieren",
        "vorkommen",
        "vorstellen",
        "wirken",
    },
}


def _is_probably_comparative(s, token):
    deps = dependants(s, token)
    return any(
        form_text(c) == "mehr" and c["deprel"] in {"advmod", "obj"}
        for c in chain(deps, chain.from_iterable(dependants(s, d) for d in deps))
    )


def extract_predicatives(s):
    for t in s:
        lemma = lemma_text(t)
        # subject predicative
        if t["upos"] in {"NOUN", "VERB", "ADJ"}:
            t_deps = dependants(s, t)
            if any(
                c["deprel"] == "cop" and lemma_text(c) in {"werden", "sein", "bleiben"}
                for c in t_deps
            ):
                if not any(c["deprel"] == "case" for c in t_deps):
                    for t_dep_1 in t_deps:
                        if t_dep_1["deprel"] == "nsubj" and t_dep_1["upos"] == "NOUN":
                            yield ("PREDC", t_dep_1["id"], t["id"])
        # object predicative
        if t["upos"] == "VERB":
            t_deps = dependants(s, t)
            for t_dep_1 in t_deps:

                def t_dep_1_pattern(pos=None, deprel=None, t_dep_1=t_dep_1):
                    if pos and t_dep_1["upos"] not in pos:
                        return False
                    if deprel and t_dep_1["deprel"] not in deprel:
                        return False
                    return True

                def t_dep_2_pattern(lemma=None, deprel=None, pos=None, t_dep_1=t_dep_1):
                    for t_dep_2 in dependants(s, t_dep_1):
                        if lemma and lemma_text(t_dep_2) not in lemma:
                            continue
                        if deprel and t_dep_2["deprel"] not in deprel:
                            continue
                        if pos and t_dep_2["upos"] not in pos:
                            continue
                        return True
                    return False

                if t_dep_1_pattern({"VERB"}):
                    # skip full verbs/particle  + aux
                    if feat(t_dep_1, "VerbForm") in {"Fin", "Part"}:
                        if t_dep_2_pattern(pos={"AUX"}):
                            continue
                if t_dep_1_pattern({"NOUN"}, {"obl"}):
                    # case 1: als + NOUN > obl
                    if (
                        lemma in _pred_verbs["als_noun"]
                        or lemma in _pred_verbs["als_adj"]
                    ):
                        if _is_probably_comparative(s, t):
                            continue
                        if t_dep_2_pattern({"als"}, {"case"}):
                            yield ("PRED", t_dep_1["id"], t["id"])
                if t_dep_1_pattern({"ADJ", "VERB"}, {"advcl", "xcomp"}):
                    # case 2 : als + ADJ > advcl
                    if lemma in _pred_verbs["als_adj"]:
                        if _is_probably_comparative(s, t):
                            continue
                        if t_dep_2_pattern({"als"}, {"mark", "case"}):
                            yield ("PRED", t_dep_1["id"], t["id"])
                if t_dep_1_pattern({"ADJ", "NOUN"}, {"obl", "obj", "xcomp"}):
                    # case 3: für + ADJ/NOUN > obl/obj/xcomp
                    if lemma in _pred_verbs["für_adj-noun"]:
                        if t_dep_2_pattern({"für"}, {"case"}):
                            yield ("PRED", t_dep_1["id"], t["id"])
                if t_dep_1_pattern({"ADJ", "VERB"}, {"advcl"}):
                    # case 4: wie + NOUN/ADJ/VERB > obl/advcl
                    if lemma in _pred_verbs["wie_adj-noun"]:
                        if t_dep_2_pattern({"wie"}, {"case", "mark"}):
                            yield ("PRED", t_dep_1["id"], t["id"])
                if t_dep_1_pattern({"ADJ"}) and not t_dep_2_pattern({"als", "wie"}):
                    # case 5: verb + adj ohne als/wie
                    if lemma == "lassen" and t_dep_1["deprel"] == "xcomp":
                        yield ("PRED", t_dep_1["id"], t["id"])
                    elif lemma == "aussehen" and t_dep_1["deprel"] == "advcl":
                        yield ("PRED", t_dep_1["id"], t["id"])
                    elif lemma == "bleiben" and t_dep_1["deprel"] == "xcomp":
                        subjects = [
                            subj
                            for subj in t_deps
                            if subj["upos"] in {"ADJ", "NOUN", "VERB"}
                            and subj["deprel"] == "nsubj"
                        ]
                        if len(subjects) == 1:
                            yield ("PREDC", t_dep_1["id"], subjects[0]["id"])


def extract_collocs(s):
    collocs = tuple(
        chain(
            extract_by_patterns(s),
            extract_comparing_groups(s),
            extract_genitives(s),
            extract_active_subjects(s),
            extract_passive_subjects(s),
            extract_objects(s),
            extract_predicatives(s),
        )
    )
    if collocs:
        s.metadata["collocations"] = json.dumps(collocs)
    return s
