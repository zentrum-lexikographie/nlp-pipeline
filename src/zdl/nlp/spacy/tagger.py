import argparse
import csv
import itertools
import sys

import spacy
import spacy.tokens
import thinc.api

def chunks(lst, n):
    for i in range(0, len(lst), n):
        yield lst[i:i + n]

def translate_input(dep_vocab, ner_vocab, records):
    for _, doc in itertools.groupby(records, lambda s: s[0]):
        words = []
        spaces = []
        sent_starts = []
        for sentence in doc:
            for n, token in enumerate(chunks(sentence[1:], 2)):
                form, space_after = token
                words.append(form)
                spaces.append(space_after == ' ')
                sent_starts.append(n == 0)
        yield (
            spacy.tokens.Doc(
                dep_vocab,
                words=words,
                spaces=spaces,
                sent_starts=sent_starts
            ),
            spacy.tokens.Doc(
                ner_vocab,
                words=words,
                spaces=spaces,
                sent_starts=sent_starts
            ),
        )


def translate_output(cn, dep_doc, ner_doc):
    for sn, sentence in enumerate(zip(dep_doc.sents, ner_doc.sents)):
        dep_sentence, ner_sentence = sentence
        entities = [[] for _ in range(len(ner_sentence))]
        s0 = ner_sentence.start
        for en, entity in enumerate(ner_sentence.ents):
            label = entity.label_
            en = str(en)
            for etn in range(entity.start - s0, entity.end - s0):
                entity = entities[etn]
                entity.append(label)
                entity.append(en)
        for tn, token in enumerate(dep_sentence):
            morph = token.morph.to_dict()
            is_root = token.dep_ == 'ROOT'
            record = [
                cn,
                sn,
                tn,
                ('' if is_root else token.dep_),
                ('' if is_root else (token.head.i - s0)),
                token.lemma_,
                token.is_oov,
                token.pos_,
                token.tag_,
                morph.get('Number', ''),
                morph.get('Gender', ''),
                morph.get('Case', ''),
                morph.get('Tense', ''),
                morph.get('Person', ''),
                morph.get('Mood', ''),
                morph.get('Degree', ''),
                morph.get('PunctType', ''),
                morph.get('VerbType', ''),
                morph.get('VerbForm', ''),
                morph.get('ConjType', ''),
                morph.get('PartType', ''),
                morph.get('PronType', ''),
                morph.get('AdpType', ''),
                morph.get('Definite', '')
            ]
            yield record + entities[tn]

arg_parser = argparse.ArgumentParser()
arg_parser.add_argument('--gpu', action="store_true")
arg_parser.add_argument('--gpuid', type=int, default=0)
arg_parser.add_argument('--batch', type=int, default=1)

if __name__ == '__main__':
    args = arg_parser.parse_args()

    model_suffix = 'lg'
    if args.gpu:
        model_suffix = 'dist'
        thinc.api.set_gpu_allocator("pytorch")
        thinc.api.require_gpu(args.gpuid)

    dep_tagger = spacy.load(f'de_dwds_dep_hdt_{model_suffix}')
    ner_tagger = spacy.load(f'de_dwds_ner_{model_suffix}')

    input = csv.reader(sys.stdin)
    output = csv.writer(sys.stdout)

    docs = translate_input(dep_tagger.vocab, ner_tagger.vocab, input)

    dep_docs, ner_docs = itertools.tee(docs)
    dep_docs = (doc[0] for doc in dep_docs)
    ner_docs = (doc[1] for doc in ner_docs)

    dep_docs = dep_tagger.pipe(dep_docs, batch_size=args.batch)
    ner_docs = ner_tagger.pipe(ner_docs, batch_size=args.batch)

    for n, (dep_doc, ner_doc) in enumerate(zip(dep_docs, ner_docs)):
        for record in translate_output(n, dep_doc, ner_doc):
            output.writerow(record)

    sys.stdout.flush()
