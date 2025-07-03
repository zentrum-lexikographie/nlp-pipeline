import argparse
import logging
import sys

import dwdsmor
import zdl_spacy

logger = logging.getLogger(__name__)


def load_dwdsmor(edition="open"):
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
    spacy_models = ["dist"]
    dwds_editions = ["open"]

    args = arg_parser.parse_args()
    if args.fast:
        spacy_models.append("lg")
    if args.dwdsmor_dwds:
        dwds_editions.append("dwds")

    for model_type in spacy_models:
        zdl_spacy.load(model_type)
        logger.info(f"Installed spaCy model ({model_type})")
    for edition in dwds_editions:
        dwdsmor.lemmatizer(f"zentrum-lexikographie/dwdsmor-{edition}")
        logger.info(f"Installed DWDSmor lemmatizer ({edition})")


if __name__ == "__main__":
    install()
