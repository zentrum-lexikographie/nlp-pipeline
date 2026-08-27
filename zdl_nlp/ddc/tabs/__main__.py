import argparse
import re
from pathlib import Path

from conllu.models import Token, TokenList

from ..conllu import serialize, text
from . import ddc_tabs_to_conll

_xml_frag = re.compile(r"</?[^>]+>")


def is_xml_frag(sentence):
    return _xml_frag.search(text(sentence)) is not None


arg_parser = argparse.ArgumentParser(description="Convert DDC-Tabs to CoNLL-U")
arg_parser.add_argument(
    "-o",
    "--output-file",
    help="output CoNLL-U file",
    type=argparse.FileType("w"),
    default="-",
)
arg_parser.add_argument(
    "-p",
    "--pattern",
    help="Glob pattern for DDC-Tabs files in dirs",
    default="**/*.tabs",
)
arg_parser.add_argument(
    "ddc_tabs_path", help="input DDC-Tabs dirs/files", type=Path, nargs="*"
)


def main():
    args = arg_parser.parse_args()
    for path in args.ddc_tabs_path:
        tabs_files = path.glob(args.pattern) if path.is_dir() else (path,)
        for tabs_file in sorted(tabs_files):
            with tabs_file.open("rt") as f:
                for sentence in ddc_tabs_to_conll(f):
                    if is_xml_frag(sentence):
                        if sentence.metadata:
                            sentence = TokenList(
                                [Token({"id": "1", "form": "---"})], sentence.metadata
                            )
                        else:
                            continue
                    args.output_file.write(serialize(sentence))


if __name__ == "__main__":
    main()
