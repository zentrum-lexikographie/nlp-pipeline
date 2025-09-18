import json

from conllu.parser import DEFAULT_FIELDS
from conllu.serializer import serialize_field


def hit_set(sentence):
    return set(json.loads(sentence.metadata.get("hits", "[]")))


def gdex_score(sentence):
    return float(sentence.metadata.get("gdex", "0.0"))


def lemma_text(token):
    return (
        (token.get("misc") or {}).get("CompoundVerb")
        or token.get("lemma")
        or token.get("form")
    )


def is_space_after(token):
    return (token.get("misc") or {}).get("SpaceAfter", "Yes") != "No"


def token_text(token):
    return token.get("form", "") + (" " if is_space_after(token) else "")


def text(sentence):
    return "".join(token_text(t) for t in sentence)


def marked_token_text(token, is_marked):
    text = token.get("form", "")
    if is_marked:
        text = f"<t>{text}</t>"
    text += " " if is_space_after(token) else ""
    return text


def marked_text(sentence):
    if hits := hit_set(sentence):
        return "".join(
            marked_token_text(t, tn in hits) for tn, t in enumerate(sentence, 1)
        )
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
