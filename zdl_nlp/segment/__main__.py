import argparse

from ..conllu import serialize
from . import segment

arg_parser = argparse.ArgumentParser(
    description="Segment plain text into sentences and tokens"
)
arg_parser.add_argument(
    "-i",
    "--input-file",
    help="Plain text input file (stdin by default)",
    type=argparse.FileType("r"),
    default="-",
)
arg_parser.add_argument(
    "-o",
    "--output-file",
    help="CoNLL-U output file (stdout by default)",
    type=argparse.FileType("w"),
    default="-",
)


args = arg_parser.parse_args()
for sentence in segment(args.input_file.read()):
    args.output_file.write(serialize(sentence))
