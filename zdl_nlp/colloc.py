import argparse
import json
from collections import defaultdict
from itertools import chain

import conllu
from tqdm import tqdm

from .conllu import serialize

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
    "PRED": {"desc": "Prädikativ", "tags": ("adj", "noun", "verb")},
    "SUBJA": {"desc": "Subjekt", "tags": ("adj", "noun", "verb")},
    "SUBJP": {"desc": "Passivsubjekt", "patterns": (("nsubj:pass", "verb", "noun"),)},
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


def extract_by_patterns(tokens):
    for t_idx, t in enumerate(tokens):
        t_n = t_idx + 1
        t_head_1_n = int(t["head"])
        if t_head_1_n <= 0:
            # token is root
            continue
        t_head_1 = tokens[t_head_1_n - 1]
        t_deprel = t["deprel"]
        if pattern := pattern_index.get(t_deprel):
            if colloc := pattern[(t_head_1["upos"], t["upos"])]:
                yield (colloc, t_head_1_n, t_n)
        t_head_2_n = int(t_head_1["head"])
        if t_head_2_n <= 0:
            # token head is root, cannot make ternary relation
            continue
        if pattern := pattern_index.get((t_head_1["deprel"], t_deprel)):
            t_head_2 = tokens[t_head_2_n - 1]
            if colloc := pattern.get((t_head_2["upos"], t_head_1["upos"], t["upos"])):
                if colloc == "KON":
                    yield (colloc, t_head_2_n, t_head_1_n)
                else:
                    yield (colloc, t_head_2_n, t_head_1_n, t_n)


def extract_comparing_groups(tokens):
    for t in tokens:
        t_head_1_n = int(t["head"])
        if (
            t_head_1_n <= 0
            or t["deprel"] != "case"
            or t["upos"] != "CCONJ"
            or t["form"] not in ["als", "wie"]
        ):
            # token is root
            continue
        t_head_1 = tokens[t_head_1_n - 1]
        t_head_2_n = int(t_head_1["head"])
        if (
            t_head_2_n <= 0
            or t_head_1["deprel"] not in {"obl", "nmod"}
            or t_head_1["upos"] != "NOUN"
        ):
            # token head is root, cannot make ternary relation
            continue
        t_head_2 = tokens[int(t_head_2_n) - 1]
        if t["form"] == "als":
            # and t_head_2["upos"] != 'ADJ':
            # expect relations with 'als' to relate to an adjective
            # TODO: preferably check for comparative
            # (https://universaldependencies.org/u/feat/Degree.html)
            continue
        if t_head_2["upos"] in {"ADJ", "VERB", "NOUN"}:
            yield ("KOM", t_head_2_n, t_head_1_n)


def dependants(tokens, head_n):
    return [t for t in tokens if t["head"] == head_n]


def extract_predicatives(tokens):
    # TODO: extend for object predicative relations
    # (https://www.deutschplus.net/pages/Pradikativ)
    for t in tokens:
        # subject predicative
        if t["upos"] in {"NOUN", "VERB", "ADJ"}:
            t_deps = tuple(dependants(tokens, t["id"]))
            if any(t["deprel"] == "cop" and t["upos"] == "AUX" for t in t_deps):
                if not any(t["deprel"] == "case" for t in t_deps):
                    for t_dep_1 in t_deps:
                        if t_dep_1["deprel"] == "nsubj" and t_dep_1["upos"] == "NOUN":
                            yield ("PRED", t_dep_1["id"], t["id"])
        # object predicative
        if t["upos"] == "VERB":
            t_deps = tuple(dependants(tokens, t["id"]))
            for t_dep_1 in t_deps:
                if t_dep_1["upos"] not in {"VERB", "ADJ", "NOUN"}:
                    continue
                if t_dep_1["deprel"] not in {"obj", "obl"}:
                    continue
                # ++ 'advcl', 'xcomp' if any(c.token.rel in
                # {'mark', 'case'} and c.token.tag in {'CCONJ',
                # 'ADP'} for c in obj.children):
                t_deps_2 = dependants(tokens, t_dep_1["id"])
                if any(t["form"] in {"als", "für"} for t in t_deps_2):
                    yield ("PRED", t["id"], t_dep_1["id"])


def has_case(token, case):
    return (token["feats"] or {}).get("Case") == case


def extract_genitives(tokens):
    for t in tokens:
        if t["upos"] != "NOUN":
            continue
        t_deps_1 = dependants(tokens, t["id"])
        for t_dep_1 in t_deps_1:
            if t_dep_1["deprel"] != "nmod" or t_dep_1["upos"] != "NOUN":
                continue
            t_deps_2 = dependants(tokens, t_dep_1["id"])
            if not has_case(t_dep_1, "Gen") or not any(
                has_case(t_dep_2, "Gen") for t_dep_2 in t_deps_2
            ):
                continue
            if any(t_dep_2["deprel"] == "case" for t_dep_2 in t_deps_2):
                continue
            yield ("GMOD", t["id"], t_dep_1["id"])


def extract_active_subjects(tokens):
    for t in tokens:
        t_deps_1 = dependants(tokens, t["id"])
        if any(t["deprel"] == "cop" for t in t_deps_1):
            continue
        if t["upos"] not in {"NOUN", "VERB", "ADJ"}:
            continue
        for t_dep_1 in t_deps_1:
            if t_dep_1["deprel"] == "nsubj" and t_dep_1["upos"] == "NOUN":
                yield ("SUBJA", t["id"], t_dep_1["id"])


def extract_objects(tokens):
    for t in tokens:
        if t["upos"] != "VERB":
            continue
        t_deps_1 = dependants(tokens, t["id"])
        for t_dep_1 in t_deps_1:
            if t_dep_1["deprel"] not in {"obj", "obl:arg"} or t_dep_1["upos"] != "NOUN":
                continue
            t_deps_2 = dependants(tokens, t_dep_1["id"])
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


def extract_collocs(sentence):
    collocs = tuple(
        chain(
            extract_by_patterns(sentence),
            extract_objects(sentence),
            extract_predicatives(sentence),
            extract_genitives(sentence),
            extract_comparing_groups(sentence),
            extract_active_subjects(sentence),
        )
    )
    if collocs:
        sentence.metadata["collocations"] = json.dumps(collocs)
    return sentence


arg_parser = argparse.ArgumentParser(description="Add collocation annotations")
arg_parser.add_argument(
    "-i",
    "--input-file",
    help="input CoNLL-U file to annotate",
    type=argparse.FileType("r"),
    default="-",
)
arg_parser.add_argument(
    "-o",
    "--output-file",
    help="output CoNLL-U file with (updated) annotations",
    type=argparse.FileType("w"),
    default="-",
)
arg_parser.add_argument("-p", "--progress", help="Show progress", action="store_true")


def main():
    args = arg_parser.parse_args()
    sentences = conllu.parse_incr(args.input_file)
    progress = None
    if args.progress:
        progress = tqdm(
            desc="Extracting collocations",
            unit=" tokens",
            unit_scale=True,
        )
    sentences = (extract_collocs(s) for s in sentences)
    out = args.output_file
    for sentence in sentences:
        out.write(serialize(sentence))
        if progress is not None:
            progress.update(len(sentence))


if __name__ == "__main__":
    main()
