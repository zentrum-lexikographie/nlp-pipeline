import contextlib
import csv
import itertools
import sys

import click
import trankit

import zdl.resources


def translate_input(records):
    words = []
    for record in records:
        if len(record) == 1:
            continue
        elif len(record) == 0:
            if len(words) > 0:
                yield words
                words = []
        elif len(record) == 2:
            words.append(record[0])
    if len(words) > 0:
        yield words


def translate_output(doc):
    for sentence in doc['sentences']:
        for ti, token in enumerate(sentence['tokens']):
            morph = {}
            feats = token.get('feats', '')
            if len(feats) > 0:
                for kv in feats.split('|'):
                    k, v = kv.split('=')
                    morph[k] = v
            deprel = token.get('deprel', '')
            yield [
                token.get('upos', ''),
                token.get('xpos', ''),
                deprel,
                ('' if deprel == 'root' else str(token.get('head', ''))),
                morph.get('Case', ''),
                morph.get('Gender', ''),
                morph.get('Number', ''),
                morph.get('Person', ''),
                morph.get('Tense', ''),
                morph.get('Mood', ''),
                morph.get('Degree', ''),
                morph.get('VerbForm', ''),
                morph.get('PronType', '')
            ]
        yield []


def partition_all(n, iterable):
    it = iter(iterable)
    while True:
        chunk = list(itertools.islice(it, n))
        if not chunk:
            return
        yield chunk


resources = zdl.resources.bucket('trankit')


@click.command()
@click.option('-m', '--model', default='german-hdt')
@click.option('-b', '--batch', default=1000)
def main(model, batch):
    with contextlib.redirect_stdout(sys.stderr):
        nlp = trankit.Pipeline(model, cache_dir=resources)

    input = csv.reader(sys.stdin)
    output = csv.writer(sys.stdout)

    sentences = translate_input(input)
    batches = partition_all(batch, sentences)
    for batch in batches:
        doc = nlp.posdep(batch)
        result = translate_output(doc)
        for record in result:
            output.writerow(record)
        sys.stdout.flush()


if __name__ == '__main__':
    main()
