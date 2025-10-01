FROM nvidia/cuda:12.9.1-base-ubuntu24.04

RUN apt-get update && \
    apt-get install -y git python3-full python3-pip python3-dev python-is-python3 && \
    rm -rf /var/lib/apt/lists/*

RUN python3 -m venv /venv

ENV PATH="/venv/bin:$PATH"

RUN pip install --no-cache-dir\
    sentence-transformers[openvino]\
    spacy[transformers]\
    cupy-cuda12x

WORKDIR /nlp

COPY ./ .

RUN pip install --no-cache-dir .
