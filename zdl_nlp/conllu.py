import json

from conllu.parser import DEFAULT_FIELDS
from conllu.serializer import serialize_field


def hit_set(sentence):
    return set(json.loads(sentence.metadata.get("hits", "[]")))


def gdex_score(sentence):
    return float(sentence.metadata.get("gdex", "0.0"))


def collocs(sentence):
    return json.loads(sentence.metadata.get("collocations", "[]"))


def hit_collocs(sentence, hits=None, collocations=None):
    hits = hits or hit_set(sentence)
    collocations = collocations or collocs(sentence)
    for collocation in collocations:
        colloc_type, *collocates = collocation[:3]
        collocates = set(collocates)
        if hits & collocates:
            for c in collocates - hits:
                yield (sentence[c - 1], collocation)


def feat(token, k, default_val=None):
    return (token.get("feats") or {}).get(k, default_val)


def misc_attr(token, k, default_val=None):
    return (token.get("misc") or {}).get(k, default_val)


def form_text(token):
    return token.get("form", "")


def lemma_text(token):
    return misc_attr(token, "CompoundVerb") or token.get("lemma") or form_text(token)


def is_space_after(token):
    return misc_attr(token, "SpaceAfter", "Yes") != "No"


def token_text(token):
    return form_text(token) + (" " if is_space_after(token) else "")


def text(sentence):
    return "".join(token_text(t) for t in sentence)


def marked_token_text(token, tag="t"):
    text = f"<{tag}>{form_text(token)}</{tag}>"
    text += " " if is_space_after(token) else ""
    return text


def marked_text(sentence, mark_collocations=False):
    if hits := hit_set(sentence):
        collocs = hit_collocs(sentence, hits) if mark_collocations else tuple()
        collocs = set(t["id"] for t, _c in collocs)
        texts = []
        for tn, t in enumerate(sentence, 1):
            if tn in hits:
                texts.append(marked_token_text(t, "t"))
            elif tn in collocs:
                texts.append(marked_token_text(t, "c"))
            else:
                texts.append(token_text(t))
        return "".join(texts)
    else:
        return text(sentence)


def serialize(sentence):
    lines = []

    if sentence.metadata:
        for key, value in sentence.metadata.items():
            if value:
                line = f"# {key} = {value}"
            else:
                line = f"# {key}"
            lines.append(line)

    for token in sentence:
        line = "\t".join(serialize_field(token.get(k)) for k in DEFAULT_FIELDS)
        lines.append(line)

    return "\n".join(lines) + "\n\n"
