import contextlib
import csv
import subprocess
import sys
import warnings

import click
import spacy
import spacy.tokens

import zdl.resources

resources_dir = zdl.resources.bucket('spacy')
sys.path.append(resources_dir)


def model_url(model):
    return f"https://huggingface.co/reneknaebel/{model}/resolve/main/{model}-any-py3-none-any.whl"


def download_model(model):
    subprocess.check_call(
        [sys.executable, '-m', 'pip', 'install',
         '--no-deps', f"--target={resources_dir}",
         model_url(model)],
        stdout=sys.stderr
    )


def translate_input(vocab, records):
    words = []
    spaces = []
    sent_starts = []
    for record in records:
        if len(record) == 1:
            continue
        elif len(record) == 0:
            if len(words) > 0:
                yield spacy.tokens.Doc(
                    vocab,
                    words=words,
                    spaces=spaces,
                    sent_starts=sent_starts
                )
                words = []
                spaces = []
                sent_starts = []
        elif len(record) == 2:
            words.append(record[0])
            spaces.append(record[1] == "1")
            sent_starts.append(len(words) == 1)
    if len(words) > 0:
        yield spacy.tokens.Doc(
            vocab,
            words=words,
            spaces=spaces,
            sent_starts=sent_starts
        )


def translate_output(sentences):
    for sentence in sentences:
        entities = [[] for _ in range(len(sentence))]
        for ei, entity in enumerate(sentence.ents):
            label = entity.label_
            ei = str(ei)
            for ti in range(entity.start, entity.end):
                entity = entities[ti]
                entity.append(label)
                entity.append(ei)
        for ti, token in enumerate(sentence):
            morph = token.morph.to_dict()
            record_base = [
                token.lemma_,
                token.pos_,
                token.tag_,
                token.dep_,
                ('' if token.dep_ == 'ROOT' else token.head.i),
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
            yield record_base + entities[ti]
        yield []


@click.command()
@click.option('-m', '--model', default='de_dep_hdt_sm')
@click.option('-b', '--batch', default=1000)
@click.option('-p', '--parallel', default=1)
@click.option('-g', '--gpu', is_flag=True)
def main(model, batch, parallel, gpu):
    if gpu:
        spacy.require_gpu()
    warnings.filterwarnings('ignore', '.*de_dep_hdt.*')
    try:
        nlp = spacy.load(model)
    except Exception:
        download_model(model)
        nlp = spacy.load(model)
    input = csv.reader(sys.stdin)
    output = csv.writer(sys.stdout)

    sentences = translate_input(nlp.vocab, input)
    docs = nlp.pipe(sentences, n_process=parallel, batch_size=batch)
    result = translate_output(docs)

    for record in result:
        output.writerow(record)
        sys.stdout.flush()


if __name__ == '__main__':
    main()
