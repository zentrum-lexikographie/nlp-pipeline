import datetime
import json
import re
from itertools import batched, chain, islice
from time import sleep

import backoff
import psycopg
from dwds_wic_sbert import WiCTransformer
from pgvector.psycopg import register_vector
from requests.exceptions import RequestException

from .annotate import create_pipe
from .conllu import form_text, hit_collocs, hit_set, lemma_text, marked_text, serialize
from .ddc import dstar_collections
from .dedupe import dedupe
from .env import config
from .korap import korap_instances
from .log import logger

_quotes_re = re.compile(r"(['\"])")


def escape_ddc_term(s):
    return _quotes_re.sub(r"\\\1", s)


def query_dstar(collections, lemmata, limit=1000):
    q_lemmata = (escape_ddc_term(ql) for ql in lemmata)
    q = " && ".join((f"('{ql}' || '@{ql}')" for ql in q_lemmata))
    page_size = min(limit, 1000) if limit > 0 else 1000
    for c in collections:
        try:
            sentences = c.query(q, page_size, timeout=5)
            sentences = dedupe(sentences, filter_duplicates=True)
            sentences = islice(sentences, limit)
            for sentence in sentences:
                yield sentence
        except TimeoutError:
            logger.exception("Timeout querying DDC instance '%s' with '%s'", c.name, q)


_cosmas_reserved_re = re.compile(r"[ ()#,\"]")


def escape_korap_term(s):
    return _cosmas_reserved_re.sub("", s)


def is_large_sentence(sentence):
    return len(sentence) > 100


def query_korap(instances, lemmata, limit=100):
    q_lemmata = (escape_korap_term(ql) for ql in lemmata)
    q = " /s0 ".join((f"(&{ql} or {ql})" for ql in q_lemmata))
    for ki in instances:
        try:
            sentences = ki.query(q)
            sentences = (s for s in sentences if not is_large_sentence(s))
            # TODO: COSMAS disjunction queries yield duplicate results
            sentences = dedupe(sentences, filter_duplicates=True)
            sentences = islice(sentences, limit)
            for sentence in sentences:
                yield sentence
        except RequestException:
            logger.exception("Error querying KorAP instance '%s' with '%s'", ki.name, q)


def is_korap_hit(sentence):
    return sentence.metadata.get("newdoc id", "").startswith("urn:korap")


def fix_korap_hits(sentence, lemma_set):
    if not is_korap_hit(sentence):
        return sentence
    hits = tuple(sorted(hit_set(sentence)))
    first_hit = hits[0]
    last_hit = hits[-1]
    fixed_hits = []
    for tn, t in enumerate(sentence, 1):
        if tn < first_hit:
            continue
        if tn > last_hit:
            continue
        if tn == first_hit or tn == last_hit:
            fixed_hits.append(tn)
        elif lemma_text(t) in lemma_set or form_text(t) in lemma_set:
            fixed_hits.append(tn)
    sentence.metadata["hits"] = json.dumps(fixed_hits)
    return sentence


def main():
    dstar_config = config.get("ZDL_NLP_DSTAR_COLLECTIONS", "dwdsxl")
    dstar = tuple(dstar_collections().get(c) for c in dstar_config.split(","))
    assert all(dstar), dstar_config

    korap = korap_instances.values() if not config.get("ZDL_NLP_NO_KORAP") else None

    dstar_limit = int(config.get("ZDL_NLP_DSTAR_QUERY_LIMIT", "1000"))
    korap_limit = int(config.get("ZDL_NLP_KORAP_QUERY_LIMIT", "100"))

    nlp_batch_size = int(config.get("ZDL_NLP_BATCH_SIZE", "128"))
    nlp_parallel = int(config.get("ZDL_NLP_PARALLEL", "-1"))
    nlp_gpus_config = config.get("ZDL_NLP_GPU_IDS", "")
    nlp_gpus = tuple(int(g.strip()) for g in nlp_gpus_config.split(",") if g)

    nlp = create_pipe(gpus=nlp_gpus, batch_size=nlp_batch_size, n_procs=nlp_parallel)

    wic_tf = WiCTransformer.load()

    def query_examples(c, lemmata):
        lemma_set = set(lemmata)
        sentences = query_dstar(dstar, lemmata, dstar_limit)
        if korap:
            sentences = chain(sentences, query_korap(korap, lemmata, korap_limit))

        sentences = nlp(sentences)
        sentences = dedupe(sentences, filter_duplicates=True)
        sentences = (fix_korap_hits(s, lemma_set) for s in sentences)

        for s_batch in batched(sentences, 32):
            embeddings = wic_tf.encode(
                [marked_text(s) for s in s_batch],
                batch_size=nlp_batch_size,
                show_progress_bar=False,
            )
            for sentence, embedding in zip(s_batch, embeddings):
                yield (sentence, embedding.tolist())

    sql_select_job = """
    SELECT id, lexemes from example_request WHERE retrieved IS NULL
    ORDER BY requested ASC LIMIT 1
    FOR UPDATE SKIP LOCKED
    """
    sql_update_job_status = """
    UPDATE example_request SET retrieved = NOW() WHERE id = %s
    """
    sql_insert_collocations = """
    INSERT INTO example_collocs (req_id, n, collocation) VALUES (%s, %s, %s)
    """
    sql_copy_examples = """
    COPY example (req_id, n, txt, gdex, doc, bibl, conll, ex_year, ex_date, embedding)
    FROM STDIN WITH (FORMAT BINARY)
    """
    sql_copy_example_types = [
        "int4",
        "int4",
        "text",
        "float8",
        "varchar",
        "text",
        "text",
        "int2",
        "date",
        "vector",
    ]

    def process_db_job(db):
        with db.transaction(), db.cursor() as c:
            next_request = c.execute(sql_select_job).fetchone()
            if not next_request:
                return False
            req_id, lexemes = next_request
            logger.info(f"Processing job #{req_id:,d}: {repr(lexemes)}")
            examples = query_examples(c, lexemes)
            n = 0
            for example_batch in batched(examples, 128):
                collocations = []
                with c.copy(sql_copy_examples) as db_examples:
                    db_examples.set_types(sql_copy_example_types)
                    for sentence, embedding in example_batch:
                        n += 1
                        meta = sentence.metadata
                        year = meta.get("year")
                        year = int(year) if year else None
                        date = meta.get("date")
                        date = datetime.date.fromisoformat(date) if date else None
                        db_examples.write_row(
                            [
                                req_id,
                                n,
                                marked_text(sentence, mark_collocations=True),
                                float(meta.get("gdex", "0.0")),
                                meta.get("newdoc id"),
                                meta.get("bibl"),
                                serialize(sentence).strip(),
                                year,
                                date,
                                embedding,
                            ]
                        )
                        s_collocations = set()
                        for t, _c in hit_collocs(sentence):
                            s_collocations.add(
                                "_".join((lemma_text(t), t.get("upos", "XY")))
                            )
                        for sc in sorted(s_collocations):
                            collocations.append((req_id, n, sc))
                if collocations:
                    c.executemany(sql_insert_collocations, collocations)
                logger.info(f"[{req_id:>10,d}] {n:,d} examples …")
            c.execute(sql_update_job_status, (req_id,))
            logger.info(f"[{req_id:>10,d}] {n:,d} examples.")
            return True

    db_conn_info = psycopg.conninfo.make_conninfo(
        "",
        host=config.get("ZDL_NLP_DB_HOST", "labor.dwds.de"),
        port=int(config.get("ZDL_NLP_DB_PORT", "5432")),
        dbname=config.get("ZDL_NLP_DB_NAME", "lex"),
        user=config.get("ZDL_NLP_DB_USER", "lex"),
        password=config.get("ZDL_NLP_DB_PASSWORD", "lex"),
        connect_timeout=int(config.get("ZDL_NLP_DB_CONNECT_TIMEOUT", "5")),
        sslmode="require",
    )

    @backoff.on_exception(backoff.expo, psycopg.errors.Error)
    def process_db_jobs():
        with psycopg.connect(db_conn_info) as db:
            register_vector(db)
            while True:
                if not process_db_job(db):
                    logger.info("Pausing")
                    sleep(10)

    try:
        while True:
            process_db_jobs()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
