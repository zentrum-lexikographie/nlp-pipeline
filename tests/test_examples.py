from itertools import islice
from pathlib import Path

import conllu
import pytest
from pytest import fixture

from zdl_nlp.annotate import setup_pipeline
from zdl_nlp.conllu import gdex_score, serialize
from zdl_nlp.ddc.corpora import corpus
from zdl_nlp.dedupe import dedupe

data_dir = (Path(__file__) / ".." / ".." / "data").resolve()


@fixture
def homographs():
    dwdswb_homographs_file = data_dir / "dwdswb-homographs.txt"
    return dwdswb_homographs_file.read_text().splitlines()


def test_homographs(homographs):
    assert len(homographs) > 1000


examples_dir = data_dir / "examples"
examples_dir.mkdir(parents=True, exist_ok=True)


@pytest.mark.skip(reason="pregenerated")
def test_examples_query(homographs):
    nlp = setup_pipeline(accurate=False, n_procs=2)
    for ln, lemma in enumerate(homographs):
        with (examples_dir / f"{ln:04d}.conll").open("wt") as conll_f:
            for corpus_name in ("ebookxl", "evidence", "webmonitor"):
                sentences = corpus(corpus_name).query(lemma)
                sentences = islice(sentences, 1000)
                sentences = nlp(sentences)
                for s in sentences:
                    conll_f.write(serialize(s))


def test_example_filtering():
    for conll_path in examples_dir.glob("*.conll"):
        with conll_path.open("rt") as conll_f:
            sentences = conllu.parse_incr(conll_f)
            sentences = (s for s in sentences if gdex_score(s) >= 0.5)
            sentences = dedupe(sentences)
            assert len(tuple(islice(sentences, 1))) > 0
