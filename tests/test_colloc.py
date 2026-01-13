from zdl_nlp.conllu import collocs, lemma_text


def test_extraction(annotate, snapshot):
    sentences = (
        "eine ganze Epoche",
        "ein Haus am Meer",
        "Maßlosigkeit war die Folge.",
        "Ohne Morgen ist der Tag nicht denkbar.",
        "Sie befindet ihn für schuldig.",
        "Analysten kritisieren die Begründung als wenig stichhaltig.",
        "Das ist das Ergebnis unserer gesamteuropäischen Geschichte.",
        "Diese Bilder wirkten wie ein Sog.",
        "eine neue Zeit begann",
        "Der Baum wurde gefällt.",
        "Anwohner protestieren heftig gegen die Autobahn.",
        "Sie empfinden das Aquarium der Natur nach.",
        "Die Reporterin überreichte König Charles das Buch.",
        "Die Zivilgesellschaft erstattet dem Staat Korruptionsgelder zurück.",
        "Die Funksprüche seiner Kinder kosten einen Fluglotsen den Job.",
        "Intel bezichtigt auch deren Kunden illegaler Handlungen.",
        "Sitze gehören zur Sonderausstattung.",
        "Der Flughafen schließt für Passagierverkehr.",
        "Diese dienen als Input für Simulationen.",
        "Sie bezeichnet Berichte als Horrormeldungen.",
        "indem sie ihnen pauschal Revanchismus uterstellen",
        "davon nutzt durchschnittlich knapp die Hälfte Internet",
        "Der Test schlug kläglich fehl.",
        "Der Sprecher lehnt eine Stellungnahme ab.",
        "Der Sprecher bezeichnet dies als Spekulation und "
        "lehnt eine Stellungnahme ab.",
        "Sie steht auf und schläft ein.",
        "Frankreichs Innenminister lehnt eine grenzüberschreitend tätige "
        "Cyber-Polizei für sein Land ab.",
        "Eine Wiedertaufe lehnt er als Irrglauben ab.",
        "das Jahrhunderthochwasser entlang der Elbe",
        "Fall der Berliner Mauer",
        "die Anteilseigner von Europas größtem Werftenverband",
        "der Prozessor erfreute sich eines mächtigen Grafikpartners",
        "Altlasten machen der Firma zu schaffen",
        "der Verein bietet dem Druck Paroli",
        "Sie arbeitet drei Tage.",
        "Sie gibt es ihr.",
        "Im Untersuchungsausschuss kann man die Zwangsmittel des "
        "Strafprozesses einsetzen.",
        "Hausbesitzer befürchtet Wertminderung seines Grundstücks",
        "Hausbesitzer befürchten Wertminderung seiner Grundstücke",
        "Expertin erwartet Niederlage Deutschlands",
        "der Präsident betont die Verantwortung aller",
        "die Regierung nennt Spitzenstellung unter den anderen Ländern als Grund",
        "Die Studie bestätigt die amerikanische Spitzenstellung bei Waffenexporten",
        "Sie wollen Änderungen beim Anteil des bezahlbaren Wohnraums erreichen.",
        "Wir festigen Deutschlands Führungsrolle im Anti-Doping-Kampf",
        "Nach einer mutmaßlichen Entführung am 9. Februar an der Natruper "
        "Straße haben die Ermittler einen weiteren Beschuldigten festgenommen.",
        "Das Opfer (26) alarmierte dort die Polizei, die den 42-jährigen "
        "Beschuldigten festnahm.",
        "Welche Personen werden als Kandidaten aufgestellt.",
        "S. war in Berlin hoch angesehen.",
        "Der Alkoholkonsum sei bereits seit Jahresanfang gesunken.",
        "Als die Einsatzkräfte eintrafen, befanden sich Menschen auf der Fahrbahn.",
        "Als die Einsatzkräfte eingetroffen waren, befanden sich Menschen "
        "auf der Fahrbahn.",
        "Das Amtsgericht sah es als erwiesen an.",
        "Bei Abstimmungen wurden mehr Stimmen gezählt, als Delegierte "
        "anwesend waren.",
        "Die Regierung will mehr ausgeben als einnehmen",
        "Das Schicksal der Vermissten bleibt ungeklärt.",
        "es bleibt mir nichts anderes übrig",
    )

    def c_desc(c, s):
        rel, *collocates = c
        return [rel] + [lemma_text(s[c - 1]) for c in collocates]

    assert snapshot == [
        [s] + [c_desc(c, s) for s in annotate(s) for c in collocs(s)] for s in sentences
    ]
