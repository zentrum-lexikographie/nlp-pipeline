def token_data(t):
    return {"deprel": t.dep_,
            "head":   t.head.i,
            "lemma":  t.lemma_,
            "is_oov": t.is_oov,
            "upos":   t.pos_,
            "xpos":   t.tag_,
            "morph":  t.morph.to_dict()}

def entity_data(e):
    return {"label": e.label_,
            "start": e.start,
            "end": e.end}
