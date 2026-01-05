import json
import logging
from dataclasses import dataclass

import requests
import requests.auth
from lxml import etree as ET
from ratelimit import limits, sleep_and_retry

from ..conllu import is_space_after
from ..env import config
from ..segment import segment
from ..utils import (
    format_date,
    join_strs,
    norm_date,
    norm_str,
    norm_strs,
    norm_title,
    norm_year,
    tags,
)

logger = logging.getLogger(__name__)


@dataclass
class OAuth(requests.auth.AuthBase):
    token: str

    def __call__(self, r):
        r.headers["Authorization"] = f"Bearer {self.token}"
        return r


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
class KorapInstance:
    name: str
    url: str
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
            logger.debug(
                f"[{self.name:>20s}][@{offset:>6,d}][={len(matches):>6,d}] {q}"
            )
            for match in matches:
                doc_id = norm_str(match.get("textSigle"))
                doc_id = f"urn:korap:{self.name}/{doc_id}"
                date = norm_date(match.get("pubDate"))
                year = norm_year(match.get("pubDate"))
                sentence, hits = parse_snippet(match.get("snippet"))
                required_fields = {
                    "newdoc id": doc_id,
                    "collection": self.name,
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
            "ql": "cosmas2",
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

        r = requests.get(self.url, params=params, headers=headers, auth=auth)
        r.raise_for_status()
        r = r.json()
        return r


korap_instances = dict()
korap_instances["deliko"] = KorapInstance(
    name="deliko", url="https://korap.dnb.de/api/v1.0/search"
)

if dereko_oauth_token := config.get("DEREKO_OAUTH_TOKEN"):
    korap_instances["dereko"] = KorapInstance(
        name="dereko",
        url="https://korap.ids-mannheim.de/api/v1.0/search",
        corpus_query="corpusSigle != /W[UDP]D.*/",
        oauth_token=dereko_oauth_token,
    )
