import argparse
import itertools
import re
import warnings
from pathlib import Path

import ebooklib
import ebooklib.epub
from bs4 import BeautifulSoup, XMLParsedAsHTMLWarning
from conllu.models import TokenList
from somajo import SoMaJo

from .nlp.conllu import serialize

warnings.filterwarnings("ignore", category=FutureWarning)
warnings.filterwarnings("ignore", category=UserWarning)
warnings.filterwarnings("ignore", category=XMLParsedAsHTMLWarning)


chunk_tag_re = re.compile("^p|h[1-6]$")

ws_run_re = re.compile("\\s+")

_no_whitespace = {"SpaceAfter": "No"}

somajo = SoMaJo("de_CMC")

arg_parser = argparse.ArgumentParser(description="ePub/ConLL-U converter")
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
    help="Glob pattern for ePub files in dirs",
    default="**/*.epub",
)
arg_parser.add_argument("epub_path", help="input ePub dirs/files", type=Path, nargs="*")

dc_ns = "http://purl.org/dc/elements/1.1/"


def get_metadata_values(book, k):
    return tuple((v for v, _ in book.get_metadata(dc_ns, k)))


def extract_title(book):
    for title in get_metadata_values(book, "title"):
        return title


def extract_metadata(book):
    return {
        "newdoc id": get_metadata_values(book, "identifier"),
        "title": extract_title(book),
        "author": get_metadata_values(book, "creator"),
        "contributor": get_metadata_values(book, "contributor"),
        "publisher": get_metadata_values(book, "publisher"),
        "date": get_metadata_values(book, "date"),
    }


def main():
    args = arg_parser.parse_args()
    doc_id = 1
    for path in args.epub_path:
        epub_files = path.glob(args.pattern) if path.is_dir() else (path,)
        for epub_file in epub_files:
            book = ebooklib.epub.read_epub(str(epub_file))
            # TODO: extract metadata
            doc_metadata = {"newdoc id": doc_id}
            doc_id += 1
            p_n = 1
            for document in book.get_items_of_type(ebooklib.ITEM_DOCUMENT):
                soup = BeautifulSoup(document.get_content(), "lxml")
                for p in soup.find_all(chunk_tag_re):
                    if "table" in {parent.name for parent in p.parents}:
                        continue
                    text = p.get_text().strip()
                    text = ws_run_re.sub(" ", text)
                    if not text:
                        continue
                    p_metadata = {"newpar id": p_n}
                    p_n += 1
                    sentences = somajo.tokenize_text((text,))
                    for s in sentences:
                        tokens = []
                        for ti, t in enumerate(s):
                            tokens.append(
                                {
                                    "id": ti + 1,
                                    "form": t.text or "---",
                                    "misc": {} if t.space_after else _no_whitespace,
                                }
                            )
                        args.output_file.write(
                            serialize(TokenList(tokens, doc_metadata | p_metadata))
                        )
                        doc_metadata = dict()
                        p_metadata = dict()


if __name__ == "__main__":
    # main()
    epub_files = Path("/home/gregor/data/zdl/bestseller").glob("**/*.epub")
    for epub_file in itertools.islice(epub_files, 10000):
        book = ebooklib.epub.read_epub(str(epub_file))
        print(repr(extract_metadata(book)))
