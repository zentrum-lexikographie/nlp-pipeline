import argparse
import itertools
from random import random

from ..conllu import serialize
from . import korap_instances

arg_parser = argparse.ArgumentParser(description="Query KorAP corpora")
arg_parser.add_argument(
    "-l",
    "--limit",
    help="limit # of sentences (100 per corpus by default)",
    type=int,
    default="100",
)
arg_parser.add_argument(
    "-o",
    "--output-file",
    help="output CoNLL-U file",
    type=argparse.FileType("w"),
    default="-",
)
arg_parser.add_argument(
    "-s",
    "--sample",
    help="sample ratio [0.0,1.0] (all sentences by default)",
    type=float,
    default="1.0",
)
arg_parser.add_argument("query", help="COSMAS-II Query")


args = arg_parser.parse_args()
sample = args.sample
limit = args.limit
for ki in korap_instances.values():
    sentences = ki.query(args.query)
    if sample < 1.0:
        sentences = (s for s in sentences if random() < sample)
    if limit > 0:
        sentences = itertools.islice(sentences, limit)
    for s in sentences:
        args.output_file.write(serialize(s))
