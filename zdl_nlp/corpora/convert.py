import argparse
import gzip
import itertools
import multiprocessing as mp
import sqlite3
from contextlib import closing
from datetime import UTC, datetime
from itertools import batched, tee
from pathlib import Path
from random import random
from time import time

import conllu

from ..annotate import create_pipe
from ..conllu import serialize
from ..log import logger
from ..tei import to_conll
from .sources import find_corpora, find_corpus_sources

arg_parser = argparse.ArgumentParser(description="Convert and annotate ZDL corpora")
arg_parser.add_argument(
    "-c", "--corpus", help="limit extraction to given corpus/corpora", nargs="*"
)
arg_parser.add_argument(
    "-l",
    "--limit",
    type=int,
    help="limit regarding the # of files/documents to process per corpus",
)
arg_parser.add_argument(
    "-p", "--parallel", type=int, help="Number of parallel conversion processes"
)
arg_parser.add_argument(
    "-s",
    "--sample",
    help="sample ratio [0.0,1.0] (all sentences by default)",
    type=float,
)
arg_parser.add_argument(
    "-B",
    "--nlp-batch-size",
    type=int,
    help="Batch size for NLP annotation",
    default="128",
)
arg_parser.add_argument(
    "-G",
    "--nlp-gpu",
    type=int,
    help="comma-separated id list of GPUs to use for NLP annotation",
    nargs="*",
)
arg_parser.add_argument(
    "-P",
    "--nlp-parallel",
    type=int,
    help="Number of parallel NLP annotation processes",
    default="-1",
)
arg_parser.add_argument(
    "source_dir", help="D* corpora source dir in TEI/XML format", type=Path
)
arg_parser.add_argument(
    "target_dir", help="target dir with annotated source i CoNLL-U format", type=Path
)

_init_manifest_sql = (
    (
        "CREATE TABLE IF NOT EXISTS document ("
        "corpus VARCHAR(32) NOT NULL,"
        "path VARCHAR(128) NOT NULL,"
        "digest CHAR(64) NOT NULL,"
        "imported INTEGER NOT NULL,"
        "import_path VARCHAR(64) NOT NULL"
        ")"
    ),
)


def write_manifest(db_file, q):
    with closing(sqlite3.connect(db_file, autocommit=True)) as db:
        logger.info("Initializing manifest database")
        for stmt in _init_manifest_sql:
            logger.debug(stmt)
            db.execute(stmt)

        while entry := q.get():
            if len(entry) == 2:
                corpus, path = entry
                db.execute(
                    "UPDATE document SET imported = ? WHERE corpus = ? and path = ?",
                    (int(time()), corpus, path),
                )
            else:
                corpus, path, digest, import_path = entry
                db.execute(
                    (
                        "INSERT INTO document "
                        "(corpus, path, digest, imported, import_path) "
                        "VALUES (?, ?, ?, ?, ?)"
                    ),
                    (corpus, path, digest, int(time()), import_path),
                )


def to_conll_str(document):
    corpus, path, xml_file, digest = document
    logger.info(f"[{corpus:16s}] {path}")
    conll_str = "".join(serialize(s) for s in to_conll(corpus, path, xml_file))
    return path, xml_file, digest, conll_str


def main():
    args = arg_parser.parse_args()

    corpus_filter = set(c for c in (args.corpus or tuple()) if c)

    if not args.target_dir.is_dir():
        args.target_dir.mkdir(parents=True, exist_ok=True)

    bucket_dir = args.target_dir / datetime.now(UTC).isoformat()
    bucket_dir.mkdir()

    db_file = args.target_dir / "manifest.db"
    db_queue = mp.Queue()
    db_proc = mp.Process(target=write_manifest, args=(str(db_file), db_queue))
    db_proc.start()

    with mp.Pool(args.parallel) as pp:
        logger.info("Initializing NLP pipeline")
        nlp = create_pipe(
            gpus=tuple(args.nlp_gpu or []),
            batch_size=args.nlp_batch_size,
            n_procs=args.nlp_parallel,
        )
        corpora = sorted(find_corpora(args.source_dir))
        for corpus, source_dir in corpora:
            if corpus_filter and corpus not in corpus_filter:
                logger.info(f"Skipping {corpus}")
                continue

            with closing(sqlite3.connect(db_file, autocommit=True)) as db:
                digests = db.execute(
                    (
                        "SELECT path, digest FROM document WHERE corpus = ? "
                        "ORDER BY path, imported"
                    ),
                    (corpus,),
                )
                digests = {path: digest for path, digest, *_ in digests}

            def files_to_process(corpus=corpus, source_dir=source_dir, digests=digests):
                corpus_source_files = find_corpus_sources(corpus, source_dir)
                for corpus, path, xml_file, digest in corpus_source_files:
                    if digest == digests.get(str(path)):
                        db_queue.put((corpus, str(path)))
                    else:
                        yield corpus, str(path), str(xml_file), digest

            source_files = files_to_process()
            if args.sample:
                source_files = (f for f in source_files if random() < args.sample)
            if args.limit:
                source_files = itertools.islice(source_files, args.limit)

            conll_files = pp.imap(to_conll_str, source_files, 32)

            def annotated_files(files=conll_files):
                sentences = (
                    (path, digest, sentence)
                    for path, xml_file, digest, conll_str in files
                    for sentence in conllu.parse(conll_str)
                )
                s_mult = tee(sentences, 2)
                files = ((path, digest) for path, digest, _ in s_mult[0])
                sentences = nlp(sentence for _, _, sentence in s_mult[1])

                file_path = None
                file_digest = None
                file_content = []
                for f, s in zip(files, sentences):
                    path, digest = f
                    if file_path != path:
                        if file_content:
                            file_content = "".join(serialize(s) for s in file_content)
                            yield file_path, file_digest, file_content
                        file_path = path
                        file_digest = digest
                        file_content = []
                    file_content.append(s)
                if file_content:
                    file_content = "".join(serialize(s) for s in file_content)
                    yield file_path, file_digest, file_content

            batches = batched(annotated_files(), 1024)
            for batch_n, batch in enumerate(batches):
                conll_file = bucket_dir / f"{batch_n:010d}.{corpus}.conll.gz"
                with gzip.open(conll_file, "wt") as cf:
                    for _, _, conll_str in batch:
                        cf.write(conll_str)
                conll_file_str = str(conll_file.relative_to(args.target_dir))
                for path, digest, _ in batch:
                    db_queue.put((corpus, path, digest, conll_file_str))
    db_queue.put(None)
    db_proc.join()


if __name__ == "__main__":
    main()
