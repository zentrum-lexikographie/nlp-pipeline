import argparse
import itertools
import json
import logging
import socket
import struct
from dataclasses import dataclass
from functools import cache, cached_property
from random import random
from typing import Tuple

import requests
from conllu.models import Token, TokenList
from requests.auth import HTTPBasicAuth

from ..conllu import serialize
from ..env import config

logger = logging.getLogger(__name__)


def request(endpoint, cmd):
    s = None
    try:
        host, port = endpoint
        logger.debug("[%s:%d] %s", host, port, repr(cmd))
        cmd = cmd.encode("utf-8")
        s = socket.create_connection(endpoint)
        s.sendall(struct.pack("<I", len(cmd)) + cmd)
        response_len, *_ = struct.unpack("<I", s.recv(4))
        response = b""
        while response_len > len(response):
            response += s.recv(1024)
        return json.loads(response.decode("utf-8"))
    finally:
        if s is not None:
            s.close()


def query(endpoint, q, offset=0, limit=10, timeout=5):
    params = " ".join(map(str, (offset, limit, timeout)))
    cmd = "\x01".join(("run_query Distributed", q, "json", params))
    return request(endpoint, cmd)


_no_whitespace = {"SpaceAfter": "No"}


@dataclass
class Corpus:
    corpus_name: str
    endpoint: Tuple[str, int]

    @cached_property
    def info(self):
        return request(self.endpoint, "info")

    def query(self, q, page_size=1000, offset=0, timeout=30):
        total = None
        while total is None or offset < total:
            result = query(self.endpoint, q, offset, page_size, timeout)
            total = result.get("nhits_", 0) if not total else total
            hits = result.get("hits_")
            if not hits:
                break
            for data in hits:
                fields = ("hit", *data["meta_"]["indices_"])
                _left_ctx, tokens, _right_ctx = data["ctx_"]
                last = len(tokens) - 1
                tokens = [dict(zip(fields, t, strict=True)) for t in tokens]
                token_list = []
                token_hits = []
                for tn, t in enumerate(tokens, 1):
                    # "ws" = "space_before"; translate to "space_after"
                    next_token = tokens[tn] if tn <= last else None
                    space_after = next_token.get("ws") == "1" if next_token else False
                    misc = {} if space_after else _no_whitespace.copy()
                    if orig := t.get("u"):
                        misc["Orig"] = orig
                    if norm := t.get("v"):
                        misc["Norm"] = norm
                    token_list.append(
                        Token(
                            {
                                "id": tn,
                                "form": t.get("w") or "---",
                                "lemma": t.get("l"),
                                "xpos": t.get("p"),
                                "misc": misc,
                            }
                        )
                    )
                    if t.get("hit") == 1:
                        token_hits.append(tn)
                doc_id = ":".join(
                    (data["meta_"]["collection"], data["meta_"]["basename"])
                )
                yield TokenList(
                    token_list,
                    {
                        "newdoc id": doc_id,
                        "bibl": data["meta_"].get("bibl", ""),
                        "date": data["meta_"].get("date_", ""),
                        "hits": json.dumps(token_hits),
                    },
                )
            offset += len(hits)


@cache
def endpoints():
    endpoints_user = config.get("DDC_CORPORA_USER")
    endpoints_password = config.get("DDC_CORPORA_PASSWORD")
    endpoints_auth = (
        HTTPBasicAuth(endpoints_user, endpoints_password)
        if endpoints_user and endpoints_password
        else None
    )

    url = (
        "https://ddc.dwds.de/dstar/intern.perl"
        if endpoints_auth
        else "https://ddc.dwds.de/dstar/"
    )
    r = requests.get(url, params={"f": "json"}, auth=endpoints_auth)
    r.raise_for_status()
    return {e["corpus"]: (e["host"], int(e["port"])) for e in r.json()}


def available_corpora():
    return sorted(endpoints().keys())


def corpus(corpus_name):
    endpoint = endpoints().get(corpus_name)
    return Corpus(corpus_name, endpoint) if endpoint else None


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
    "-c", "--corpus", help="DDC corpora to query (dwdsxl by default)", nargs="*"
)
arg_parser.add_argument("ddc_query", help="DDC Query")


def main():
    args = arg_parser.parse_args()
    sample = args.sample
    limit = args.limit
    page_size = min(limit, 1000) if limit > 0 else 1000
    for corpus_name in args.corpus or ("dwdsxl",):
        sentences = corpus(corpus_name).query(args.ddc_query, page_size)
        if sample < 1.0:
            sentences = (s for s in sentences if random() < sample)
        if limit > 0:
            sentences = itertools.islice(sentences, limit)
        for s in sentences:
            args.output_file.write(serialize(s))


if __name__ == "__main__":
    main()
