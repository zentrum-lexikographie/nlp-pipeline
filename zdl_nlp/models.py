import argparse
import logging
import sys

import dwdsmor
import zdl_spacy

logger = logging.getLogger(__name__)


def install_spacy_models(accurate=True):
    model_type = "dist" if accurate else "lg"
    zdl_spacy.load(model_type)
    logger.info(f"Installed spaCy model ({model_type})")


def install_dwdsmor_edition(edition):
    load_dwdsmor(edition)
    logger.info(f"Installed DWDSmor lemmatizer ({edition})")


def load_dwdsmor(edition):
    edition = f"zentrum-lexikographie/dwdsmor-{edition}"
    return dwdsmor.lemmatizer(edition)


arg_parser = argparse.ArgumentParser(description="Install NLP models")
arg_parser.add_argument(
    "-d", "--dwdsmor-dwds", help="Install DWDS-Edition of DWDSmor", action="store_true"
)
arg_parser.add_argument(
    "-f", "--fast", help="Install CPU-optimized spaCy models", action="store_true"
)


def install():
    logging.basicConfig(
        format="%(asctime)s – %(levelname)s – %(message)s",
        level=logging.INFO,
        stream=sys.stdout,
    )
    args = arg_parser.parse_args()
    install_spacy_models(accurate=True)
    if args.fast:
        install_spacy_models(accurate=False)
    install_dwdsmor_edition("open")
    if args.dwdsmor_dwds:
        install_dwdsmor_edition("dwds")


if __name__ == "__main__":
    install()
