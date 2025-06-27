# NLP Pipeline @ ZDL

_Combining Off-the-Shelf and Custom Components_


## Installation

    pip install -U pip setuptools
    pip install git+https://github.com/zentrum-lexikographie/nlp-pipeline@vx.y.z

Replace `vx.y.z` with the current version.

    zdl-nlp-install-models

Add `-f`, if you would like to install CPU-optimized models and `-d`,
if you have access to the DWDS edition of DWDSmor. In the latter case,
log into your Hugging Face account beforehand, i. e.:

    $ huggingface-cli login
    […]
    $ zdl-nlp-install-models -d -f
    2025-06-27 12:58:02,352 – INFO – Installed spaCy model (dist)
    2025-06-27 12:58:11,420 – INFO – Installed spaCy model (lg)
    2025-06-27 12:58:11,963 – INFO – Installed DWDSmor lemmatizer (open)
    2025-06-27 12:58:12,309 – INFO – Installed DWDSmor lemmatizer (dwds)

## Usage Example

Annotate a random sentence from a corpus of political speeches:

    $ zdl-nlp-polspeech -s 0.01 -l 1 | zdl-nlp-annotate -a --dwdsmor-dwds
    # newdoc id = http://www.auswaertiges-amt.de/DE/Infoservice/Presse/Reden/2010/101014-Pieper-Dokkyo-Universität.html
    # bibl = Cornelia Pieper. Rede Staatsministerin Pieper: "150 Jahre Wissenschaftsbeziehungen Deutschland-Japan – ein Schatz für die Zukunft". 2010-10-14. o.O.
    # date = 2010-10-14
    # entities = [["ORG", 13, 14]]
    # gdex = 0.87078857421875
    # lang = de
    # collocations = [["ADV", 2, 4], ["PP", 2, 8, 5], ["ATTR", 8, 7]]
    1	Ich	ich	PRON	PPER	Case=Nom|Number=Sing|Person=1|PronType=Prs	2	nsubj	_	_
    2	freue	freuen	VERB	VVFIN	Mood=Ind|Number=Sing|Person=1|Tense=Pres|VerbForm=Fin	0	root	_	_
    3	mich	ich	PRON	PRF	Case=Acc|Number=Sing|Person=1|PronType=Prs|Reflex=Yes	2	expl:pv	_	_
    4	sehr	sehr	ADV	ADV	Degree=Pos	2	advmod	_	_
    5	über	über	ADP	APPR	AdpType=Prep|Case=Acc	8	case	_	_
    6	den	die	DET	ART	Case=Acc|Definite=Def|Gender=Masc|Number=Sing|PronType=Art	8	det	_	_
    7	warmherzigen	warmherzig	ADJ	ADJA	Case=Acc|Degree=Pos|Gender=Masc|Number=Sing	8	amod	_	_
    8	Empfang	Empfang	NOUN	NN	Gender=Masc|Number=Sing	2	obl	_	SpaceAfter=No
    9	,	,	PUNCT	$,	PunctType=Comm	15	punct	_	_
    10	den	die	PRON	PRELS	Case=Acc|Gender=Masc|Number=Sing|PronType=Dem,Rel	15	obj	_	_
    11	mir	ich	PRON	PPER	Case=Dat|Number=Sing|Person=1|PronType=Prs	15	obl:arg	_	_
    12	die	die	DET	ART	Case=Nom|Definite=Def|Gender=Fem|Number=Sing|PronType=Art	13	det	_	_
    13	Dokkyo	Dokkyo	NOUN	NN	_	15	nsubj	_	_
    14	Universität	Universität	NOUN	NN	Gender=Fem|Number=Sing	13	appos	_	_
    15	bereitet	bereiten	VERB	VVFIN	Mood=Ind|Number=Sing|Person=3|Tense=Pres|VerbForm=Fin	8	acl	_	SpaceAfter=No
    16	.	.	PUNCT	$.	PunctType=Peri	2	punct	_	_

## Development Setup

    pip install -U pip pip-tools setuptools
    pip install -e .[dev]

## Analyze TEI schema (element classes)

    (cd tei-schema && clojure -X:extract) >zdl_nlp/tei_schema.json

## License

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU Lesser Public License for more details.

You should have received a copy of the GNU Lesser Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.
