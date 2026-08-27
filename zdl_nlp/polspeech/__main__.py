import argparse

from ..conllu import serialize
from . import as_conll

arg_parser = argparse.ArgumentParser(description="Download German Political Speeches")
arg_parser.add_argument(
    "-l",
    "--limit",
    help="limit # of sentences (no limit by default)",
    type=int,
    default="0",
)
arg_parser.add_argument(
    "-o",
    "--output-file",
    help="CoNLL-U output file (stdout by default)",
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

args = arg_parser.parse_args()
for sentence in as_conll(args.limit, args.sample):
    args.output_file.write(serialize(sentence))
