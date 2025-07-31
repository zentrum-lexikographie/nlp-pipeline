import argparse
import itertools
import re
from pathlib import Path
from random import random

from .conllu import serialize
from .tei import corpus_files, to_conll


def corpora(base_dir):
    collections = {"asv", "genios", "kern", "sz"}
    for p in base_dir.iterdir():
        if not p.is_dir():
            continue
        if p.name in collections:
            for cp in p.iterdir():
                if not cp.is_dir():
                    continue
                yield cp
        else:
            yield p


def find_xml_sources(corpus_dir):
    xml_dirs = [
        c for c in corpus_dir.iterdir() if c.is_dir() and c.name.startswith("xml-")
    ]
    if xml_dirs:
        xml_dirs.sort()
        return xml_dirs[-1]
    ts_dirs = [
        c
        for c in corpus_dir.iterdir()
        if c.is_dir() and re.match(r"[0-9]{4}-[0-9]{2}-[0-9]{2}", c.name)
    ]
    if ts_dirs:
        ts_dirs.sort()
        return ts_dirs[-1]
    return None


def sources(base_dir):
    for c in corpora(base_dir):
        if "legacy" in c.name:
            continue
        if xml_sources := find_xml_sources(c):
            yield (c.name, str(xml_sources))


arg_parser = argparse.ArgumentParser(description="Gather ZDL corpora")
arg_parser.add_argument(
    "-o",
    "--output-file",
    help="output CoNLL-U file (defaults to stdout)",
    type=argparse.FileType("w"),
    default="-",
)
arg_parser.add_argument("corpora_path", help="input TEI/XML dirs/files", type=Path)


def main():
    args = arg_parser.parse_args()
    corpora = list(sources(args.corpora_path))
    corpora.sort()
    for corpus, corpus_path in corpora:
        input_files = corpus_files(corpus, Path(corpus_path))
        input_files = (f for f in input_files if random() < 0.01)
        input_files = itertools.islice(input_files, 10)
        for corpus, basename, xml_file in input_files:
            for s in to_conll(corpus, basename, xml_file):
                args.output_file.write(serialize(s))


if __name__ == "__main__":
    main()
