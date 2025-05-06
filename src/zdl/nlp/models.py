import argparse
import logging
import subprocess
import warnings

import dwdsmor
import spacy
import thinc.api

warnings.simplefilter(action="ignore", category=FutureWarning)

spacy_model_packages = {
    "de_hdt_dist": (
        "de_hdt_dist @ https://huggingface.co/zentrum-lexikographie/de_hdt_dist/"
        "resolve/main/de_hdt_dist-any-py3-none-any.whl"
        "#sha256=dd54e4f75b249d401ed664c406c1a021ee6733bca7c701eb4500480d473a1a8a"
    ),
    "de_hdt_lg": (
        "de_hdt_lg @ https://huggingface.co/zentrum-lexikographie/de_hdt_lg/"
        "resolve/main/de_hdt_lg-any-py3-none-any.whl"
        "#sha256=44bd0b0299865341ee1756efd60670fa148dbfd2a14d0c1d5ab99c61af08236a"
    ),
    "de_wikiner_dist": (
        "de_wikiner_dist @ https://huggingface.co/zentrum-lexikographie/"
        "de_wikiner_dist/resolve/main/de_wikiner_dist-any-py3-none-any.whl"
        "#sha256=70e3bb3cdb30bf7f945fa626c6edb52c1b44aaccc8dc35ea0bfb2a9f24551f4f"
    ),
    "de_wikiner_lg": (
        "de_wikiner_lg @ https://huggingface.co/zentrum-lexikographie/"
        "de_wikiner_lg/resolve/main/de_wikiner_lg-any-py3-none-any.whl"
        "#sha256=8305ec439cad1247bed05907b97f6db4c473d859bc4083ef4ee0f893963c5b2e"
    ),
}

logger = logging.getLogger(__name__)


def install_spacy_models(accurate=True):
    spacy_model_type = "dist" if accurate else "lg"
    for model in (f"de_hdt_{spacy_model_type}", f"de_wikiner_{spacy_model_type}"):
        try:
            spacy.load(model)
        except OSError:
            assert model in spacy_model_packages, model
            subprocess.check_call(
                ["pip", "install", "-qqq", spacy_model_packages[model]]
            )
            spacy.load(model)
        logger.info("Installed spaCy model (%s)", model)


def load_spacy(accurate=True, ner=True, gpu_id=None):
    if gpu_id is not None:
        thinc.api.set_gpu_allocator("pytorch")
        thinc.api.require_gpu(gpu_id)
    nlp = spacy.load("de_hdt_dist" if accurate else "de_hdt_lg")
    if ner:
        ner = spacy.load("de_wikiner_dist" if accurate else "de_wikiner_lg")
        ner.replace_listeners(
            "transformer" if accurate else "tok2vec", "ner", ("model.tok2vec",)
        )
        nlp.add_pipe("ner", source=ner, name="wikiner")
    nlp.add_pipe("doc_cleaner")
    return nlp


def load_dwdsmor(edition):
    edition = f"zentrum-lexikographie/dwdsmor-{edition}"
    return dwdsmor.lemmatizer(edition)


def install_dwdsmor_edition(edition):
    load_dwdsmor(edition)
    logger.info("Installed DWDSmor lemmatizer (%s)", edition)


arg_parser = argparse.ArgumentParser(description="Install NLP models")
arg_parser.add_argument(
    "-d", "--dwdsmor-dwds", help="Install DWDS-Edition of DWDSmor", action="store_true"
)
arg_parser.add_argument(
    "-f", "--fast", help="Install CPU-optimized spaCy models", action="store_true"
)


def install():
    args = arg_parser.parse_args()
    install_spacy_models(accurate=(not args.fast))
    install_dwdsmor_edition("dwds" if args.dwdsmor_dwds else "open")


if __name__ == "__main__":
    install()
