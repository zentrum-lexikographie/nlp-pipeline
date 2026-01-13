from pytest import fixture

from zdl_nlp.annotate import create_pipe
from zdl_nlp.segment import segment


@fixture(scope="module")
def nlp():
    return create_pipe()


@fixture
def annotate(nlp):
    def annotate_(txt):
        return tuple(nlp(segment(txt)))

    return annotate_
