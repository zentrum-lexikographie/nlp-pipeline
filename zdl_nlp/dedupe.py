from datasketch import MinHash, MinHashLSH

from zdl_nlp.conllu import lemma_text

excluded_pos = {"ADP", "CCONJ", "DET", "PRON", "PUNCT"}
excluded_tags = {"PTKA", "PTKZU"}


def fingerprint(sentence):
    return sorted(
        set(
            lemma_text(t)
            for t in sentence
            if t.get("upos", "_") not in excluded_pos
            and t.get("xpos", "_") not in excluded_tags
        )
    )


def dedupe(sentences, filter_duplicates=False):
    lsh = MinHashLSH(threshold=0.9, num_perm=128)
    for sn, s in enumerate(sentences):
        mh = MinHash(num_perm=128)
        for t in fingerprint(s):
            mh.update(t.encode("utf-8"))
        duplicate = False
        if lsh.query(mh):
            s.metadata["duplicate"] = "true"
            duplicate = True
        else:
            lsh.insert(str(sn), mh)
        if not filter_duplicates or not duplicate:
            yield s


def is_duplicate(sentence):
    return sentence.metadata.get("duplicate") == "true"
