import argparse
import itertools
from random import random

from ..conllu import serialize
from . import dstar_collections

arg_parser = argparse.ArgumentParser(description="Query DDC corpora")
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
arg_parser.add_argument(
    "-c", "--collection", help="D* collections to query (dwdsxl by default)", nargs="*"
)
arg_parser.add_argument(
    "-t", "--timeout", help="Socket/Query timeout in seconds", type=int
)
arg_parser.add_argument("ddc_query", help="DDC Query")


args = arg_parser.parse_args()
sample = args.sample
limit = args.limit
page_size = min(limit, 1000) if limit > 0 else 1000
timeout = args.timeout or 5
collections = args.collection or ("dwdsxl",)

assert all(c in dstar_collections() for c in collections), repr(collections)

for c in collections:
    sentences = (
        dstar_collections().get(c).query(args.ddc_query, page_size, timeout=timeout)
    )
    if sample < 1.0:
        sentences = (s for s in sentences if random() < sample)
    if limit > 0:
        sentences = itertools.islice(sentences, limit)
    for s in sentences:
        args.output_file.write(serialize(s))
