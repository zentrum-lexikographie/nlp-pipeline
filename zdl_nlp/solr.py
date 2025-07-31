import argparse
import itertools
import json
from collections import defaultdict

import conllu
import requests
from lxml import etree as ET
from lxml.builder import ElementMaker
from tqdm import tqdm

from .conllu import is_space_after

arg_parser = argparse.ArgumentParser(description="Index annotated texts")
arg_parser.add_argument(
    "-b",
    "--batch-size",
    help="# of documents to process in one batch (32 by default)",
    type=int,
    default="32",
)
arg_parser.add_argument(
    "--clear", help="Clear Solr Core before indexing", action="store_true"
)
arg_parser.add_argument(
    "-i",
    "--input-file",
    help="input CoNLL-U file to index",
    type=argparse.FileType("r"),
    default="-",
)
arg_parser.add_argument("--progress", help="Show progress", action="store_true")
arg_parser.add_argument(
    "--solr-host",
    required=True,
    help="Solr Host, i. e. 'localhost'",
)
arg_parser.add_argument(
    "--solr-core",
    required=True,
    help="Solr Core, i. e. 'corpora'",
)
arg_parser.add_argument(
    "--solr-port",
    help="Solr Port (8983 by default)",
    type=int,
    default="8983",
)


def index_update(solr_url, solr_core, update_xml):
    r = requests.post(
        f"{solr_url}/solr/{solr_core}/update",
        headers={"Content-Type": "text/xml"},
        params={"wt": "json"},
        data=ET.tostring(update_xml, encoding="unicode").encode("utf-8"),
    )
    r.raise_for_status()
    return r.json()


def index_clear(solr_url, solr_core):
    index_update(solr_url, solr_core, E.delete(E.query("*:*")))


stored_trans = str.maketrans({"=": r"\="})

token_trans = str.maketrans(
    {
        " ": r"\ ",
        ",": r"\,",
        "=": r"\=",
        "\\": r"\\",
        "\n": r"\n",
        "\r": r"\r",
        "\t": r"\t",
    }
)


def token(s):
    return s.translate(token_trans)


def stored(s):
    return f"={s.translate(stored_trans)}="


E = ElementMaker()


def doc_field(k, v):
    return E.field({"name": k}, str(v))


def index(solr_url, solr_core, batch):
    index_docs = []
    for doc in batch:
        meta_fields = []
        pn = 1
        for sn, s in enumerate(conllu.parse(doc), 1):
            md = s.metadata
            lemmata = defaultdict(list)
            for ti, t in enumerate(s, 1):
                if lemma := t.get("lemma"):
                    lemmata[ti].append(lemma)
                misc = t.get("misc") or {}
                if pv := misc.get("CompoundVerb"):
                    lemmata[ti].append(pv)
                    lemmata[int(misc.get("CompoundPrt"))].append(pv)
            entities = {}
            for entity in json.loads(md.get("entities", "[]")):
                label, *tis = entity
                for ti in tis:
                    entities[int(ti)] = label
            collocations = defaultdict(list)
            for colloc in json.loads(md.get("collocations", "[]")):
                label, t1, t2, *_ = colloc
                label = label.lower()
                t1 = int(t1)
                t2 = int(t2)
                for l1 in lemmata[t1]:
                    for l2 in lemmata[t2]:
                        collocations[t1].append((f"c#{label}", f"{l1}#{l2}"))
                        collocations[t2].append((f"c#{label}", f"{l1}#{l2}"))
                        collocations[t1].append((f"c#{label}_", f"{l2}#{l1}"))
                        collocations[t2].append((f"c#{label}_", f"{l2}#{l1}"))
            s_len = len(s)
            content = ""
            content_len = 0
            tokens = []
            for ti, t in enumerate(s, 1):
                start = content_len
                form = t.get("form", "")
                content += form
                content_len += len(form)
                end = content_len
                if not ti == s_len and is_space_after(t):
                    content += " "
                    content_len += 1
                token_se = f"s={start},e={end}"
                tokens.append(f"{token(form)},i=1,{token_se}")

                def anno(prefix, v, tokens=tokens, token_se=token_se):
                    if v:
                        tokens.append(f"{prefix}#{token(v)},i=0,{token_se}")

                for lemma in lemmata[ti]:
                    anno("", lemma)
                if entity := entities.get(ti):
                    for lemma in lemmata[ti]:
                        anno("e", lemma)
                        anno(f"e#{entity}", lemma)
                for pos in (t.get("upos"), t.get("xpos")):
                    if not pos:
                        continue
                    for lemma in lemmata[ti]:
                        anno(f"p#{pos.lower()}", lemma)
                for prefix, collocs in collocations[ti]:
                    anno(prefix, collocs)

            if sn == 1:
                doc_urn = md["newdoc id"]

                def meta_field(k, v, meta_fields=meta_fields):
                    if v:
                        meta_fields.append((k, v))

                meta_field("doc_s", doc_urn)
                meta_field("bibl_s", md.get("bibl"))
                meta_field("url_s", md.get("url"))
                meta_field("year_i", md.get("year"))
                meta_field("date_dt", md.get("date"))
                meta_field("accessed_dt", md.get("accessed"))
                meta_field("country_s", md.get("country"))
                meta_field("metaarea_s", md.get("metaarea"))
                meta_field("area_s", md.get("area"))
                meta_field("subarea_s", md.get("subarea"))
                for text_class in json.loads(md.get("textClass", "[]")):
                    meta_field("text_class_ss", text_class)

                meta_fields = tuple(meta_fields)
            if "newpar id" in md:
                pn = int(md["newpar id"][1:])

            sentence_fields = [
                ("id", f"{doc_urn}#{sn}"),
                ("p_i", str(pn)),
                ("s_i", str(sn)),
                ("text_pre", f"1 {stored(content)} {' '.join(tokens)}"),
            ]

            def sentence_field(k, v, sentence_fields=sentence_fields):
                if v:
                    sentence_fields.append((k, v))

            gdex = md.get("gdex")
            sentence_field("gdex_f", gdex)
            sentence_field("gdex_b", float(gdex) >= 0.5 if gdex else None)
            sentence_field("lang_s", md.get("lang"))

            index_docs.append(
                E.doc(
                    *(doc_field(k, v) for k, v in sentence_fields),
                    *(doc_field(k, v) for k, v in meta_fields),
                )
            )
    index_update(solr_url, solr_core, E.add(*index_docs))
    return len(index_docs)


def read_docs(lines):
    doc = ""
    for line in lines:
        if line.startswith("# newdoc id"):
            if doc:
                yield doc
            doc = line
        else:
            doc += line
    if doc:
        yield doc


def main():
    args = arg_parser.parse_args()
    solr_url = f"http://{args.solr_host}:{args.solr_port}"
    solr_core = args.solr_core
    progress = None
    if args.progress:
        progress = tqdm(
            desc="Indexing",
            unit=" sentences",
            unit_scale=True,
            smoothing=0.01,
        )
    if args.clear:
        index_clear(solr_url, solr_core)
    docs = read_docs(args.input_file)
    batches = itertools.batched(docs, args.batch_size)
    batches = (index(solr_url, solr_core, batch) for batch in batches)
    for docs_updated in batches:
        if progress is not None:
            progress.update(docs_updated)


if __name__ == "__main__":
    main()
