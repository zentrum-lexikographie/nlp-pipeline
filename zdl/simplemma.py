import csv
import itertools
import sys

import click
import simplemma


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


def partition_all(n, iterable):
    it = iter(iterable)
    while True:
        chunk = list(itertools.islice(it, n))
        if not chunk:
            return
        yield chunk


@click.command()
@click.option('-m', '--model', default='de')
@click.option('-b', '--batch', default=1000)
def main(model, batch):
    lang_data = simplemma.load_data(model)
    input = csv.reader(sys.stdin)
    output = csv.writer(sys.stdout)

    sentences = translate_input(input)
    batches = partition_all(batch, sentences)
    for batch in batches:
        for sentence in batch:
            for token in sentence:
                lemma = simplemma.lemmatize(token, lang_data, greedy=False)
                output.writerow([lemma or token])
            output.writerow([])
        sys.stdout.flush()


if __name__ == '__main__':
    main()
