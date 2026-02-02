import re
from hashlib import file_digest


def _find_source_dir(corpus_dir):
    xml_dir = corpus_dir / "xml"
    if xml_dir.is_dir():
        return xml_dir

    xml_dirs = [
        c for c in corpus_dir.iterdir() if c.is_dir() and c.name.startswith("xml-")
    ]
    if xml_dirs:
        xml_dirs.sort()
        return xml_dirs[-1]

    ts_dirs = [
        c
        for c in corpus_dir.iterdir()
        if c.is_dir() and re.match(r"[0-9]{4}-[0-9]{2}-[0-9]{2}", c.name)
    ]
    if ts_dirs:
        ts_dirs.sort()
        return ts_dirs[-1]

    return None


def _find_corpus_dirs(base_dir):
    collections = {"asv", "genios", "kern", "sz"}
    for p in base_dir.iterdir():
        if not p.is_dir():
            continue
        if p.name in collections:
            for cp in p.iterdir():
                if not cp.is_dir():
                    continue
                yield cp
        else:
            yield p


def _sha256(f):
    with f.open("rb") as fh:
        return file_digest(fh, "sha256").hexdigest()


def find_corpora(base_dir):
    for c in _find_corpus_dirs(base_dir):
        if "legacy" in c.name:
            continue
        if source_dir := _find_source_dir(c):
            yield (c.name, source_dir)


def find_corpus_sources(corpus, source_dir):
    for dir_path, _subdirs, files in source_dir.walk():
        for f in files:
            if f.endswith(".xml"):
                f = dir_path / f
                yield (corpus, f.relative_to(source_dir), f, _sha256(f))
