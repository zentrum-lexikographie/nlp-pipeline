import argparse
import itertools
import re
import warnings
from pathlib import Path
from random import random
from tempfile import NamedTemporaryFile, TemporaryDirectory
from urllib.request import urlretrieve
from zipfile import ZipFile

from conllu.models import TokenList
from lxml import etree

from .conllu import serialize
from .segment import segment

warnings.simplefilter(action="ignore", category=FutureWarning)


def parse_doc(xml_file):
    xml_doc = etree.parse(xml_file)
    for text_el in xml_doc.iter("text"):
        for content in text_el.iter("rohtext"):
            content = re.sub(r"\\s+", " ", content.text.strip())
            for si, s in enumerate(segment(content)):
                if si == 0:
                    bibl = ". ".join(
                        (
                            text_el.attrib.get("person") or "Anonym",
                            text_el.attrib.get("titel") or "ohne Titel",
                            text_el.attrib.get("datum") or "n.d",
                            text_el.attrib.get("ort") or "o.O",
                            "",
                        )
                    )
                    yield TokenList(
                        s,
                        {
                            "newdoc id": text_el.attrib.get("url"),
                            "bibl": bibl,
                            "date": text_el.attrib.get("datum"),
                        },
                    )
                else:
                    yield s


def as_conll(limit=0, sample=1.0):
    with TemporaryDirectory() as data_dir, NamedTemporaryFile(
        "w", delete=False
    ) as zip_file:
        try:
            zip_file.close()
            urlretrieve(
                "https://politische-reden.eu/German-Political-Speeches-Corpus.zip",
                zip_file.name,
            )
            with ZipFile(zip_file.name) as zf:
                zf.extractall(data_dir)
            sentences = (
                s
                for xml_file in Path(data_dir).rglob("*.xml")
                for s in parse_doc(xml_file)
            )
            if sample < 1.0:
                sentences = (
                    s
                    for s in sentences
                    if s.metadata.get("newdoc id") or random() < sample
                )
            if limit > 0:
                sentences = itertools.islice(sentences, limit)
            for sentence in sentences:
                yield sentence
        finally:
            Path(zip_file.name).unlink()


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


def main():
    args = arg_parser.parse_args()
    for sentence in as_conll(args.limit, args.sample):
        args.output_file.write(serialize(sentence))


if __name__ == "__main__":
    main()
