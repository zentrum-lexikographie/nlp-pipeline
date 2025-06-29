import argparse
import itertools
import uuid

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
        doc_uri = f"uuid:{str(uuid.uuid1())}"
        for sn, s in enumerate(conllu.parse(doc), 1):
            content = ""
            content_len = 0
            tokens = []
            for t in s:
                start = content_len
                form = t.get("form", "")
                content += form
                content_len += len(form)
                end = content_len
                if is_space_after(t):
                    content += " "
                    content_len += 1
                token_se = f"s={start},e={end}"
                tokens.append(f"{token(form)},i=1,{token_se}")

                def anno(prefix, v, tokens=tokens, token_se=token_se):
                    if v:
                        tokens.append(f"{prefix}#{token(v)},i=0,{token_se}")

                anno("", t.get("lemma", ""))
                anno("p", t.get("upos", ""))
                anno("pos", t.get("xpos", ""))
            index_docs.append(
                E.doc(
                    doc_field("id", f"{doc_uri}#{sn}"),
                    doc_field("doc", doc_uri),
                    doc_field("text_pre", f"1 {stored(content)} {' '.join(tokens)}"),
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
