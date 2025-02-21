## Installation

    pip install -U pip setuptools
    pip install 'zdl-nlp[cuda] @ git+https://github.com/zentrum-lexikographie/nlp-pipeline@vx.y.z'

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

    clojure -X:tei-schema >src/main/python/zdl/xml/tei_schema.json

## Import simplemma lexicons

    $ clojure -X:python zdl.nlp.simplemma.lexicon/import!
    WARNING: Using incubator modules: jdk.incubator.foreign, jdk.incubator.vector
    2024-06-05T14:48:13.277Z textmaschine INFO [zdl.nlp.simplemma.lexicon:55] - Cloning simplemma repository to /tmp/73e622f0-1266-49a8-a369-523f331d690f17904144672877893130
    2024-06-05T14:48:37.069Z textmaschine INFO [zdl.nlp.simplemma.lexicon:45] - Reading simplemma lexicon /tmp/73e622f0-1266-49a8-a369-523f331d690f17904144672877893130/simplemma/strategies/dictionaries/data/de.plzma'
    2024-06-05T14:49:17.923Z textmaschine INFO [zdl.nlp.simplemma.lexicon:45] - Reading simplemma lexicon /tmp/73e622f0-1266-49a8-a369-523f331d690f17904144672877893130/simplemma/strategies/dictionaries/data/en.plzma'
    2024-06-05T14:49:25.607Z textmaschine INFO [zdl.nlp.simplemma.lexicon:45] - Reading simplemma lexicon /tmp/73e622f0-1266-49a8-a369-523f331d690f17904144672877893130/simplemma/strategies/dictionaries/data/fr.plzma'
    2024-06-05T14:49:38.767Z textmaschine INFO [zdl.nlp.simplemma.lexicon:60] - Writing 1,117,692 entries to src/zdl/nlp/simplemma/lexicon.edn

## Bibliography

* Elisabeth Eder, Ulrike Krieg-Holz, and Udo Hahn. 2019. At the Lower
  End of Language—Exploring the Vulgar and Obscene Side of German. In
  Proceedings of the Third Workshop on Abusive Language Online, pages
  119–128, Florence, Italy. Association for Computational
  Linguistics. [Link](https://aclanthology.org/W19-3513)

## Acknowledgments

The GDEX implementation makes use of
[VulGer](https://aclanthology.org/W19-3513), a lexicon covering words
from the lower end of the German language register — terms typically
considered rough, vulgar, or obscene. VulGer is used under the terms
of the CC-BY-SA license.
