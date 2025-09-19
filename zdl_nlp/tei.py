import argparse
import atexit
import datetime
import itertools
import json
import multiprocessing
import os
import re
import urllib.parse
from functools import cache
from pathlib import Path
from random import random

import lxml.etree as ET

from .conllu import serialize
from .segment import segment


@cache
def tei_tag(tag):
    """Qualifies a tag name with the TEI namespace."""
    return "{http://www.tei-c.org/ns/1.0}" + tag


tag_schema_file = (Path(__file__) / ".." / "tei_schema.json").resolve()
tag_categories = {}

for category, tags in json.loads(tag_schema_file.read_text()).items():
    tag_categories[category] = set({tei_tag(t) for t in tags})


@cache
def tei_tag_set(*tags):
    """Creates a set of tags in the TEI namespace."""
    return {tei_tag(tag) for tag in tags}


def is_chunk(element):
    return element.tag in tag_categories["chunks"]


def is_nested_chunk(element):
    parent = element.getparent()
    while parent is not None:
        if is_chunk(parent):
            return True
        parent = parent.getparent()
    return False


def iter_chunks(tei_element):
    """Iterates through all chunk-level elements of a tree."""
    for text_body in tei_element.iter(tei_tag("body")):
        for element in text_body.iter():
            if is_chunk(element) and not is_nested_chunk(element):
                yield element


def has_content(text):
    """The given text is not empty."""
    return text is not None and len(text) > 0


def is_ws_element(element):
    return element.tag in tei_tag_set("cb", "lb", "pb") and "no" != element.attrib.get(
        "break", ""
    )


ws_run = re.compile(r"\s+")


def _add_segment(text, segment):
    segment = ws_run.sub(" ", segment)
    if text == "" or text[-1] == " ":
        segment = segment.lstrip()
    return text + segment


def extract_text(element, text="", strip=True):
    if is_ws_element(element):
        text = _add_segment(text, " ")
    if has_content(element.text):
        text = _add_segment(text, element.text)
    for child in element:
        text = extract_text(child, text, False)
    if has_content(element.tail):
        text = _add_segment(text, element.tail)
    if strip:
        text = text.strip()
    return text


def text_class_to_json(text_class):
    if text_class:
        return json.dumps(list(sorted(text_class)))


def extract_date(date):
    if date:
        try:
            datetime.date.fromisoformat(date)
            return date
        except ValueError:
            return None


year_re = re.compile(r"^[0-9]{4}")


def date_to_year(date):
    if date:
        for year_match in year_re.finditer(date):
            return year_match.group()


def extract_metadata(tei_header):
    bibl = None
    url = None
    date = None
    access_date = None
    text_class = set()
    country = None
    metaarea = None
    area = None
    subarea = None
    for file_desc in tei_header.iter(tei_tag("fileDesc")):
        for notes_stmt in file_desc.iter(tei_tag("notesStmt")):
            for note in notes_stmt.iter(tei_tag("note")):
                if note.get("type", "ddc") != "ddc":
                    continue
                for idno in note.iter(tei_tag("idno")):
                    if idno.get("type", "bibl") != "bibl":
                        continue
                    if bibl:
                        continue
                    bibl = extract_text(idno)
        for source_desc in file_desc.iter(tei_tag("sourceDesc")):
            if source_desc.get("n", "ddc") != "ddc":
                continue
            if source_desc.get("id", "orig") != "orig":
                continue
            for bibl_full in source_desc.iter(tei_tag("biblFull")):
                for pub_stmt in bibl_full.iter(tei_tag("publicationStmt")):
                    for date_el in pub_stmt.iter(tei_tag("date")):
                        if date:
                            continue
                        if date_el.get("type", "first") not in {"first", "publication"}:
                            continue
                        date = extract_text(date_el)
                    for ptr in pub_stmt.iter(tei_tag("ptr")):
                        if url:
                            continue
                        if ptr.get("type", "") != "URL":
                            continue
                        url = ptr.get("target")
            for bibl_el in source_desc.iter(tei_tag("bibl")):
                if bibl:
                    continue
                bibl = extract_text(bibl_el)
    for profile_desc in tei_header.iter(tei_tag("profileDesc")):
        for text_class_el in profile_desc.iter(tei_tag("textClass")):
            for keyword in text_class_el.iter(tei_tag("keyword")):
                for term in keyword.iter(tei_tag("term")):
                    if term := extract_text(term):
                        text_class.add(term)
            for class_code in text_class_el.iter(tei_tag("classCode")):
                if class_code := extract_text(class_code):
                    text_class.add(class_code)
        for setting_desc in profile_desc.iter(tei_tag("settingDesc")):
            for place in setting_desc.iter(tei_tag("place")):
                for country_el in place.iter(tei_tag("country")):
                    if country:
                        continue
                    country = extract_text(country_el)
                for region in place.iter(tei_tag("region")):
                    region_type = region.get("type", "")
                    if not metaarea and region_type == "metaarea":
                        metaarea = extract_text(region)
                    elif not subarea and region_type == "subarea":
                        subarea = extract_text(region)
                    elif not area:
                        area = extract_text(region)
        for creation in profile_desc.iter(tei_tag("creation")):
            for date_el in creation.iter(tei_tag("date")):
                if access_date:
                    continue
                if date_el.get("type", "") != "download":
                    continue
                access_date = extract_text(date_el)

    year = date_to_year(date or access_date)
    access_date = extract_date(access_date)
    date = extract_date(date)
    date = date or access_date

    metadata = (
        ("bibl", bibl),
        ("url", url),
        ("accessed", access_date),
        ("date", date),
        ("year", year),
        ("textClass", text_class_to_json(text_class)),
        ("country", country),
        ("metaarea", metaarea),
        ("area", area),
        ("subarea", subarea),
    )
    return {k: v for k, v in metadata if v}


def to_conll(corpus, basename, xml_file):
    doc_urn = f"urn:corpus:{corpus}:{urllib.parse.quote(basename)}"
    doc_n = 0
    metadata_stack = [{"collection": corpus}]
    for event, element in ET.iterparse(xml_file, events=("start", "end"), recover=True):
        if event == "start" and element.tag in tei_tag_set("teiCorpus", "TEI"):
            metadata = {}
            for tei_header in element.iterchildren(tei_tag("teiHeader")):
                metadata = extract_metadata(tei_header)
                break
            metadata_stack.append(metadata)
        if event == "start" and element.tag == tei_tag("TEI"):
            doc_n += 1
            for ci, c in enumerate(iter_chunks(element), 1):
                for si, s in enumerate(segment(extract_text(c)), 1):
                    if si == 1:
                        if ci == 1:
                            doc_id = doc_urn
                            if doc_n > 1:
                                doc_id = "/".join((doc_id, f"d{doc_n}"))
                            s.metadata["newdoc id"] = doc_id
                            for metadata in metadata_stack:
                                for k, v in metadata.items():
                                    s.metadata[k] = v
                        s.metadata["newpar id"] = f"p{ci}"
                    yield s
        if event == "end" and element.tag in tei_tag_set("teiCorpus", "TEI"):
            metadata_stack.pop()
            element.clear()


arg_parser = argparse.ArgumentParser(description="Convert TEI/XML to CoNLL-U")
arg_parser.add_argument(
    "-c",
    "--corpus",
    help="base URN of converted corpus",
    default="zdl",
)
arg_parser.add_argument(
    "-l",
    "--limit",
    help="limit # of documents (no limit by default)",
    type=int,
    default="0",
)
arg_parser.add_argument(
    "-o",
    "--output-file",
    help="output CoNLL-U file (defaults to stdout)",
    type=argparse.FileType("w"),
    default="-",
)
arg_parser.add_argument(
    "-p",
    "--parallel",
    help="# of parallel conversions (1 by default)",
    type=int,
    default="-1",
)
arg_parser.add_argument(
    "-s",
    "--sample",
    help="sample ratio [0.0,1.0] (all documents by default)",
    type=float,
    default="1.0",
)
arg_parser.add_argument(
    "tei_xml_path", help="input TEI/XML dirs/files", type=Path, nargs="*"
)


def corpus_files(corpus, path):
    if path.is_dir():
        for dir_path, _subdirs, files in path.walk():
            for f in files:
                if f.endswith(".xml"):
                    f = dir_path / f
                    yield (corpus, str(f.relative_to(path)), f)
    else:
        yield (corpus, path.name, path)


def corpus_file_to_conll_str(corpus_file):
    corpus, basename, xml_file = corpus_file
    return "".join(serialize(s) for s in to_conll(corpus, basename, xml_file))


def main():
    args = arg_parser.parse_args()
    if len(args.tei_xml_path) > 0:

        def files(p):
            return corpus_files(args.corpus, p)

        input_files = (f for p in args.tei_xml_path for f in files(p))
        if args.sample < 1.0:
            input_files = (xfs for xfs in input_files if random() < args.sample)
        if args.limit > 0:
            input_files = itertools.islice(input_files, args.limit)
    else:
        input_files = ((args.corpus, "stdin", argparse.FileType("rb")("-")),)
    n_procs = args.parallel
    if n_procs >= 0:
        n_procs = n_procs or len(os.sched_getaffinity(0))
        pool = multiprocessing.Pool(n_procs)

        @atexit.register
        def terminate_pool():
            pool.terminate()

        for chunk in pool.imap(corpus_file_to_conll_str, input_files):
            args.output_file.write(chunk)
    else:
        for corpus, basename, xml_file in input_files:
            for s in to_conll(corpus, basename, xml_file):
                args.output_file.write(serialize(s))


if __name__ == "__main__":
    main()
