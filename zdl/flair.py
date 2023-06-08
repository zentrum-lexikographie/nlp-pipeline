import csv
import itertools
import logging
import pathlib
import sys

import click
import flair
import flair.data
import flair.models

import zdl.resources


def translate_input(records):
    words = []
    for record in records:
        if len(record) == 1:
            continue
        elif len(record) == 0:
            if len(words) > 0:
                yield flair.data.Sentence(words)
                words = []
        elif len(record) == 2:
            words.append(record[0])
    if len(words) > 0:
        yield words


def translate_output(sentences):
    for sentence in sentences:
        tokens = [[(i + 1)] for i in range(len(sentence))]
        for ei, entity in enumerate(sentence.get_spans('ner')):
            label = entity.tag
            score = entity.score
            ei = str(ei)
            start = entity.tokens[0].idx - 1
            end = entity.tokens[-1].idx
            for ti in range(start, end):
                token = tokens[ti]
                token.append(label)
                token.append(ei)
                token.append(score)
        for token in tokens:
            yield token
        yield []


def partition_all(n, iterable):
    it = iter(iterable)
    while True:
        chunk = list(itertools.islice(it, n))
        if not chunk:
            return
        yield chunk


flair.cache_root = pathlib.Path(zdl.resources.bucket('flair'))


@click.command()
@click.option('-m', '--model', default='flair/ner-german-large')
@click.option('-b', '--batch', default=1000)
def main(model, batch):
    logging.disable(logging.CRITICAL)
    # See https://github.com/flairNLP/flair/issues/464 for instructions
    # on how to configure torch device, i.e. running NER tagging on
    # second GPU.
    nlp = flair.models.SequenceTagger.load(model)
    logging.disable(logging.NOTSET)

    input = csv.reader(sys.stdin)
    output = csv.writer(sys.stdout)

    sentences = translate_input(input)
    batches = partition_all(batch, sentences)
    for batch in batches:
        nlp.predict(batch)
        result = translate_output(batch)
        for record in result:
            output.writerow(record)
        sys.stdout.flush()


if __name__ == '__main__':
    main()
