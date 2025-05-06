# NLP @ ZDL

## Installation

    pip install -U pip setuptools
    pip install 'zdl_nlp[cuda] @ git+https://github.com/zentrum-lexikographie/nlp-pipeline@vx.y.z'

Replace `vx.y.z` with the current version, and optionally remove the
extra `[cuda]` if you do not use a GPU.

    zdl-nlp-install-models

Add `-f`, if you would like to install CPU-optimized models and `-d`,
if you have access to the DWDS edition of DWDSmor. In the latter case,
log into your Hugging Face account beforehand, i. e.:

    $ huggingface-cli login
    […]
    $ zdl-nlp-install-models -d
    2025-02-21 13:32:24 - zdl.nlp.models - Installed spaCy model (de_hdt_dist)
    2025-02-21 13:32:26 - zdl.nlp.models - Installed spaCy model (de_wikiner_dist)
    2025-02-21 13:32:27 - zdl.nlp.models - Installed DWDSmor lemmatizer (dwds)

## Development Setup

    pip install -U pip pip-tools setuptools
    pip install -e .[dev]

For CUDA-based inference:

    pip install -e .[dev,cuda]


## Analyze TEI schema (element classes)

    clojure -X:tei-schema >src/zdl/xml/tei_schema.json
