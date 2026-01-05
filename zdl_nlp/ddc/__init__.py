import json
import logging
import re
import socket
import struct
from dataclasses import dataclass
from functools import cache, cached_property
from typing import Tuple

import requests
from conllu.models import Token, TokenList
from requests.auth import HTTPBasicAuth

from ..env import config
from ..utils import norm_date, norm_year, tags

logger = logging.getLogger(__name__)


def ddc_request(endpoint, cmd, timeout=None):
    s = None
    try:
        host, port = endpoint
        cmd = cmd.encode("utf-8")
        s = socket.create_connection(endpoint, timeout=timeout)
        s.sendall(struct.pack("<I", len(cmd)) + cmd)
        response_len, *_ = struct.unpack("<I", s.recv(4))
        response = b""
        while response_len > len(response):
            response += s.recv(1024)
        return json.loads(response.decode("utf-8"))
    finally:
        if s is not None:
            s.close()


def ddc_query(endpoint, q, offset=0, limit=10, timeout=None):
    params = " ".join(map(str, (offset, limit, timeout or 5)))
    cmd = "\x01".join(("run_query Distributed", q, "json", params))
    return ddc_request(endpoint, cmd, timeout=timeout)


_no_whitespace = {"SpaceAfter": "No"}
_tag_split_re = re.compile(r":")


@dataclass
class DStarCollection:
    name: str
    endpoint: Tuple[str, int]

    @cached_property
    def info(self):
        return ddc_request(self.endpoint, "info")

    def query(self, q, page_size=1000, offset=0, timeout=None):
        total = None
        while total is None or offset < total:
            result = ddc_query(self.endpoint, q, offset, page_size, timeout)
            total = result.get("nhits_", 0) if not total else total
            hits = result.get("hits_")
            if not hits:
                break
            logger.debug(f"[{self.name:>20s}][@{offset:>6,d}][={len(hits):>6,d}] {q}")
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
                collection = data["meta_"]["collection"]
                doc_id = data["meta_"]["basename"]
                doc_id = f"urn:ddc:{collection}/{doc_id}"
                date = data["meta_"].get("date_", "")
                year = norm_year(date)
                date = norm_date(date)
                text_classes = tags(data["meta_"].get("textClass"), _tag_split_re)
                metadata = {
                    k: v
                    for k, v in (
                        ("newdoc id", doc_id),
                        ("collection", collection),
                        ("bibl", data["meta_"].get("bibl")),
                        ("date", str(date) if date else None),
                        ("year", str(year) if year else None),
                        ("hits", json.dumps(token_hits)),
                        ("textClasses", text_classes),
                    )
                    if v
                }
                yield TokenList(token_list, metadata)
            offset += len(hits)


@cache
def dstar_collections():
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
    return {
        e["corpus"]: DStarCollection(e["corpus"], (e["host"], int(e["port"])))
        for e in r.json()
    }
