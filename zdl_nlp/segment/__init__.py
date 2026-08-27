from conllu.models import TokenList
from somajo import SoMaJo

somajo = SoMaJo("de_CMC")

_no_whitespace = {"SpaceAfter": "No"}


def segment(*texts):
    for s in somajo.tokenize_text(texts):
        tokens = []
        for ti, t in enumerate(s):
            tokens.append(
                {
                    "id": ti + 1,
                    "form": t.text or "---",
                    "misc": {} if t.space_after else _no_whitespace.copy(),
                }
            )
        yield TokenList(tokens)
