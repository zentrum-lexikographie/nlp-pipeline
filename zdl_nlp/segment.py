import argparse

from conllu.models import TokenList
from somajo import SoMaJo

from .conllu import serialize

somajo = SoMaJo("de_CMC")

_no_whitespace = {"SpaceAfter": "No"}


def segment(*texts):
    for s in somajo.tokenize_text(texts):
        tokens = []
        for ti, t in enumerate(s):
            tokens.append(
                {
                    "id": ti + 1,
                    "form": t.text or "---",
                    "misc": {} if t.space_after else _no_whitespace.copy(),
                }
            )
        yield TokenList(tokens)


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


def main():
    args = arg_parser.parse_args()
    for sentence in segment(args.input_file.read()):
        args.output_file.write(serialize(sentence))


if __name__ == "__main__":
    main()
