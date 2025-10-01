import itertools
import json
import logging
import re
import ssl

from pika import BlockingConnection, ConnectionParameters, PlainCredentials, SSLOptions
from pika.exceptions import AMQPConnectionError
from requests.exceptions import RequestException
from retry import retry

import zdl_nlp.annotate
import zdl_nlp.ddc.corpora
import zdl_nlp.dedupe
import zdl_nlp.korap

from .conllu import form_text, hit_set, lemma_text, serialize
from .env import config

logger = logging.getLogger(__name__)

_quotes_re = re.compile(r"(['\"])")


def escape_ddc_term(s):
    return _quotes_re.sub(r"\\\1", s)


def ddc_query(lemmata):
    q_lemmata = (escape_ddc_term(ql) for ql in lemmata)
    q_lemmata = (f"('{ql}' || '@{ql}')" for ql in q_lemmata)
    q = " && ".join(q_lemmata)
    return q


def query_ddc(ddc_corpora, lemmata, limit=1000):
    q = ddc_query(lemmata)
    page_size = min(limit, 1000) if limit > 0 else 1000
    for corpus in ddc_corpora:
        try:
            sentences = corpus.query(q, page_size, timeout=5)
            sentences = itertools.islice(sentences, limit)
            for sentence in sentences:
                yield sentence
        except TimeoutError:
            logger.exception(
                "Timeout querying DDC instance '%s' with '%s'", corpus.corpus_name, q
            )


_cosmas_reserved_re = re.compile(r"[ ()#,\"]")


def escape_korap_term(s):
    return _cosmas_reserved_re.sub("", s)


def korap_query(lemmata):
    q_lemmata = (escape_korap_term(ql) for ql in lemmata)
    q_lemmata = (f"(&{ql} or {ql})" for ql in q_lemmata)
    q = " /s0 ".join(q_lemmata)
    return q


def is_large_sentence(sentence):
    return len(sentence) > 100


def query_korap(korap_corpora, lemmata, limit=100):
    q = korap_query(lemmata)
    for corpus in korap_corpora:
        try:
            sentences = corpus.query(q)
            sentences = (s for s in sentences if not is_large_sentence(s))
            sentences = itertools.islice(sentences, limit)
            for sentence in sentences:
                yield sentence
        except RequestException:
            logger.exception(
                "Error querying KorAP instance '%s' with '%s'", corpus.corpus_name, q
            )


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
    korap_corpora = tuple()
    if not config.get("ZDL_NLP_NO_KORAP"):
        deliko = zdl_nlp.korap.deliko
        dereko = zdl_nlp.korap.dereko
        korap_corpora = (deliko, dereko) if dereko else (deliko,)

    ddc_corpora_config = config.get("ZDL_NLP_DDC_CORPORA", "dwdsxl")
    ddc_corpora = tuple(map(zdl_nlp.ddc.corpora.corpus, ddc_corpora_config.split(",")))
    assert all(ddc_corpora), ddc_corpora_config

    corpus_query_limit = int(config.get("ZDL_NLP_QUERY_LIMIT", "100"))

    nlp_accurate = False if config.get("ZDL_NLP_CPU", "") else True
    nlp_parallel = int(config.get("ZDL_NLP_PARALLEL", "-1"))
    nlp_batch_size = int(config.get("ZDL_NLP_BATCH_SIZE", "128"))
    nlp_gpus_config = config.get("ZDL_NLP_GPU_IDS", "")
    nlp_gpus = tuple(int(g.strip()) for g in nlp_gpus_config.split(",") if g)
    nlp_dwdsmor_dwds = False if config.get("ZDL_NLP_DWDSMOR_OPEN", "") else True

    nlp = zdl_nlp.annotate.setup_pipeline(
        accurate=nlp_accurate,
        n_procs=nlp_parallel,
        batch_size=nlp_batch_size,
        gpus=nlp_gpus,
        dwdsmor_dwds=nlp_dwdsmor_dwds,
    )

    def get_examples(ch, method, properties, body):
        lemmata = body.decode("utf-8").splitlines()
        lemma_set = set(lemmata)
        preamble = f"# lemmata = {json.dumps(lemmata)}"
        sentences = itertools.chain(
            query_ddc(ddc_corpora, lemmata, corpus_query_limit),
            query_korap(korap_corpora, lemmata, corpus_query_limit),
        )
        sentences = nlp(sentences)
        sentences = zdl_nlp.dedupe.dedupe(sentences)
        sentences = (fix_korap_hits(s, lemma_set) for s in sentences)
        for batch in itertools.batched(sentences, 100):
            body = "\n".join((preamble, "".join((serialize(s) for s in batch))))
            body = body.encode("utf-8")
            ch.basic_publish(exchange="examples", routing_key="", body=body)

    queue_host = config.get("ZDL_NLP_QUEUE_HOST", "localhost")
    queue_user = config.get("ZDL_NLP_QUEUE_USER", "nlp")
    queue_password = config.get("ZDL_NLP_QUEUE_PASSWORD", "nlp")
    queue_ssl_options = None
    if config.get("ZDL_NLP_QUEUE_TLS"):
        ssl_context = ssl.create_default_context()
        ssl_context.check_hostname = False
        ssl_context.verify_mode = ssl.CERT_NONE
        queue_ssl_options = SSLOptions(context=ssl_context)

    con_credentials = PlainCredentials(queue_user, queue_password)
    con_params = ConnectionParameters(
        host=queue_host,
        credentials=con_credentials,
        ssl_options=queue_ssl_options,
        heartbeat=600,
        blocked_connection_timeout=300,
    )

    @retry(AMQPConnectionError, delay=5, jitter=(1, 3))
    def consume():
        con = BlockingConnection(con_params)
        ch = con.channel()
        try:
            ch.exchange_declare(exchange="examples", exchange_type="fanout")
            ch.queue_declare(queue="example_queries", durable=True)
            ch.basic_qos(prefetch_count=1)
            ch.basic_consume("example_queries", get_examples, auto_ack=True)
            ch.start_consuming()
        except BaseException:
            ch.stop_consuming()
            con.close()
            raise

    try:
        consume()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
