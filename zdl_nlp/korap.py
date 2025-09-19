import argparse
import itertools
import json
import re
from dataclasses import dataclass
from random import random

import requests
import requests.auth
from lxml import etree as ET
from ratelimit import limits, sleep_and_retry

from .conllu import is_space_after, serialize
from .env import config
from .segment import segment
from .utils import format_date, join_strs, norm_date, norm_str, norm_strs, norm_year


@dataclass
class OAuth(requests.auth.AuthBase):
    token: str

    def __call__(self, r):
        r.headers["Authorization"] = f"Bearer {self.token}"
        return r


_puncts = "!,-./:;?[]{}"
_puncts_re = re.compile(f"([{re.escape(_puncts)}])\\.")


def norm_title(s):
    s = norm_str(s)
    return _puncts_re.sub(r"\1", s) if s else None


_split_re = re.compile(r"[:\s]")


def tags(s):
    s = norm_str(s)
    strs = norm_strs(_split_re.split(s)) if s else None
    return json.dumps(sorted(strs)) if strs else None


def parse_bibl(match, date, year):
    author = norm_str(match.get("author"))
    title = norm_title(
        join_strs(
            ". ",
            norm_strs((norm_str(match.get("title")), norm_str(match.get("subTitle")))),
        )
    )
    title = join_strs(": ", norm_strs((author, title)))
    corpus = norm_str(match.get("corpusTitle"))
    place = norm_str(match.get("pubPlace"))
    bibl_date = format_date(date)
    bibl_date = bibl_date or str(year) if year else None
    return norm_title(join_strs(". ", norm_strs((title, corpus, place, bibl_date))))


def parse_snippet(s):
    html = ET.fromstring(f"<html>{s.strip()}</html>")
    text = ""
    char_hits = set()
    for el in html.iterdescendants():
        start = len(text)
        text += el.text if el.text else ""
        end = len(text)
        text += el.tail if el.tail else ""
        if el.tag == "mark":
            char_hits.update(range(start, end))
    offset = 0
    for s in segment(text):
        hits = []
        for tn, t in enumerate(s, 1):
            start = offset
            end = offset + len(t["form"]) + (1 if is_space_after(t) else 0)
            for t_offset in range(start, end):
                if t_offset in char_hits:
                    hits.append(tn)
                    break
            offset = end
        if hits:
            return (s, hits)

    raise AssertionError()


@dataclass
class Corpus:
    corpus_name: str
    endpoint_url: str
    corpus_query: str | None = None
    oauth_token: str | None = None

    def query(self, q, max_results=10000, max_requests=100):
        reqs = 0
        offset = 0
        while offset < max_results and reqs < max_requests:
            reqs += 1
            r = self._request(q, offset=offset)
            matches = r.get("matches", tuple())
            if not matches:
                break
            for match in matches:
                doc_id = norm_str(match.get("textSigle"))
                doc_id = f"urn:korap:{self.corpus_name}/{doc_id}"
                date = norm_date(match.get("pubDate"))
                year = norm_year(match.get("pubDate"))
                sentence, hits = parse_snippet(match.get("snippet"))
                required_fields = {
                    "newdoc id": doc_id,
                    "collection": self.corpus_name,
                    "bibl": parse_bibl(match, date, year),
                    "hits": json.dumps(hits),
                }
                optional_fields = {
                    k: v
                    for k, v in (
                        ("country", norm_str(match.get("pubPlaceKey"))),
                        ("date", str(date) if date else None),
                        ("year", str(year) if year else None),
                        ("availability", norm_str(match.get("availability"))),
                        ("topics", tags(match.get("textClass"))),
                        ("textClasses", tags(match.get("textType"))),
                    )
                    if v
                }
                sentence.metadata = {**required_fields, **optional_fields}
                yield sentence
                offset += 1
            if offset >= r.get("meta", {}).get("totalResults", 0):
                break

    @sleep_and_retry
    @limits(calls=1, period=2)
    def _request(self, q, offset=0):
        params = {
            "ql": "poliqarp",
            "q": q,
            "context": "sentence",
            "offset": str(offset),
            "count": "100",
            "fields": "@all",
            "show-snippet": "true",
        }
        if self.corpus_query:
            params["cq"] = self.corpus_query

        headers = {"user-agent": "zdl-nlp-pipeline/1.0 (https://www.zdl.org/)"}
        auth = OAuth(self.oauth_token) if self.oauth_token else None

        r = requests.get(self.endpoint_url, params=params, headers=headers, auth=auth)
        r.raise_for_status()
        r = r.json()
        return r


deliko = Corpus(corpus_name="dnb", endpoint_url="https://korap.dnb.de/api/v1.0/search")

dereko = None
if dereko_oauth_token := config.get("DEREKO_OAUTH_TOKEN"):
    dereko = Corpus(
        corpus_name="dereko",
        endpoint_url="https://korap.ids-mannheim.de/api/v1.0/search",
        corpus_query="corpusSigle != /W[UDP]D.*/",
        oauth_token=dereko_oauth_token,
    )

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
arg_parser.add_argument("query", help="Poliqarp Query")


def main():
    args = arg_parser.parse_args()
    sample = args.sample
    limit = args.limit
    corpora = (deliko, dereko) if dereko else (deliko,)
    for corpus in corpora:
        sentences = corpus.query(args.query)
        if sample < 1.0:
            sentences = (s for s in sentences if random() < sample)
        if limit > 0:
            sentences = itertools.islice(sentences, limit)
        for s in sentences:
            args.output_file.write(serialize(s))


if __name__ == "__main__":
    main()
