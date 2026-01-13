from zdl_nlp.conllu import lemma_text


def lemmata(txt):
    return tuple(lemma_text(t) for s in txt for t in s)


def test_phrasal_verb_lemmatization(annotate):
    phrasal_verbs = (
        ("einsetzen", "Denn die Läuterung setzt üblicherweise erst später ein."),
        ("nachfolgen", "Pauli folgt Ministerpräsident Günther Beckstein nach."),
        ("herausfallen", "Schwaben fällt oft hinten heraus."),
        ("hinzukommen", "Hinzu kommen unerklärliche Gliederschmerzen."),
        ("vorbeifahren", "Der Fahrer fuhr an dem Polizisten vorbei."),
        ("dazutun", "Die Klimakrise tut ihr übriges dazu."),
        ("offenstehen", "Die Tür steht offen."),
        # ("leerstehen", "Das Haus steht leer."), FIXME
    )
    simple_verbs = (
        ("vorbeisein", "Dann ist es mit der Heimlichkeit vorbei."),
        ("rechthaben", "Hat er doch recht."),
        ("rechthaben", "Recht hat sie!"),
        ("rechtgeben", "Der VGH gab dem Landratsamt recht."),
    )
    for lemma, sentence in phrasal_verbs:
        assert lemma in lemmata(annotate(sentence))
    for lemma, sentence in simple_verbs:
        assert lemma not in lemmata(annotate(sentence))


def test_contraction_lemmatization(annotate):
    contractions = (
        ("am", "an"),
        ("Am", "an"),
        ("aufs", "auf"),
        ("beim", "bei"),
        ("fürs", "für"),
        ("hinters", "hinter"),
        ("hinterm", "hinter"),
        ("im", "in"),
        ("ins", "in"),
        ("übers", "über"),
        ("überm", "über"),
        ("ums", "um"),
        ("unterm", "unter"),
        ("unters", "unter"),
        ("vorm", "vor"),
        ("vors", "vor"),
        ("vom", "von"),
        ("zum", "zu"),
        ("zur", "zu"),
        ("ans", "an"),
        ("hintern", "hinter"),
        # ("übern", "über"), FIXME
        # ("untern", "unter"), FIXME
    )
    for contracted, lemma in contractions:
        assert lemma in lemmata(annotate(contracted))
