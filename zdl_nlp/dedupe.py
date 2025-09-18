from datasketch import MinHash, MinHashLSH

from zdl_nlp.conllu import hit_set, lemma_text

excluded_pos = {"ADP", "CCONJ", "DET", "PRON", "PUNCT", "_"}
excluded_tags = {"PTKA", "PTKZU", "_"}


def fingerprint(sentence):
    hits = hit_set(sentence)
    return set(
        lemma_text(t) if tn not in hits else t.get("upos")
        for tn, t in enumerate(sentence)
        if t.get("upos", "_") not in excluded_pos
        and t.get("xpos", "_") not in excluded_tags
    )


def dedupe(sentences):
    lsh = MinHashLSH(threshold=0.9, num_perm=128)
    for sn, s in enumerate(sentences):
        mh = MinHash(num_perm=128)
        for t in fingerprint(s):
            mh.update(t.encode("utf-8"))
        if lsh.query(mh):
            # match
            continue
        lsh.insert(str(sn), mh)
        yield s
