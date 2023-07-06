^{:nextjournal.clerk/visibility {:code :hide} :nextjournal.clerk/toc true}
(ns presentation
  (:require
   [nextjournal.clerk :as clerk]
   [zdl.nlp.tokenizer :as tokenizer]))

;; # Eine neue NLP-Pipeline für das ZDL
;;
;; _Eigenentwicklungen sowie Komponenten „von der Stange”_
;;
;; ## Ausgangslage
;;
;; * aktuelle NLP-Infrastruktur basiert auf **hauseigenen Entwicklungen**, deren Autoren mehrheitlich nicht mehr am Zentrum Sprache arbeiten
;; * Weiterentwicklung mangels Know-How kaum möglich; **Anschlussfähigkeit an neuere Entwicklungen** der letzten Jahre (Stichwort: _deep learning_) **nicht gegeben**
;; * für die auf Syntaxanalyse basierenden System (Wortprofil, Gute-Belege-Extraktor) sind Nachfolgesysteme in Entwicklung oder bereits in Betrieb
;; * für die Annotation von Texten (mit [Cascading Analysis Broker – CAB](https://nbn-resolving.org/resolver?identifier=urn%3Anbn%3Ade%3Akobv%3A517-opus-55789)) sowie für die Indizierung und das Retrieval (mit [DDC](https://kaskade.dwds.de/~moocow/software/ddc/)) fehlen bislang Lösungen
;; * die **Kohäsion** der innerhalb der IT-Infrastruktur eingesetzten Systeme wird **nicht mehr** durch einzelne Personen **verbürgt**, oder durch Versäulung stabilisiert

;;
;; ## Lösungsansatz
;;
;; * statt wie bislang auf Eigenentwicklungen zu setzen und voraussetzungsreich Werkzeuge selbst zu entwickeln , begreifen wir das ZDL als **Systemintegrator** solcher Werkzeuge
;; * entscheidend hierbei ist die Wahl der richtigen, d. h. **geeigneten Komponenten** sowie die Fähigkeit, **diese aufeinander abzustimmen**

;; ## Ein Beispiel
;;
;; * Frage aus einem [FAZ-Interview](https://www.faz.net/aktuell/wirtschaft/unternehmen/hans-und-jakob-uszkoreit-wurde-chatgpt-zu-frueh-auf-die-menschheit-losgelassen-18949623.html?premium=0x0dcb06b0a3b68c815d3ba0f735ed6813dfaed6e3be144c896be8d63e2019584d) mit Hans und Jakob Uszkoreit sowie einer fiktiven Antwort als Anwendungsfall

^{::clerk/visibility {:result :hide}}
(def sentences
  (str "FAZ: Wenn der Erfinder des berühmten Turing-Tests, Alan Turing, "
       "diese Modelle sehen würde, würde er aber doch sagen: "
       "Das ist eine Intelligenz. Die KI besteht ja den Test. "
       "Alan Turing: I would have failed that test!"))

;; ## Segmentierung
;;
;; * Wort- und Satzsegmentierung mit [KorAP-Tokenizer](https://github.com/KorAP/KorAP-Tokenizer)
;; * basierend auf einem deterministischen, endlichen Automaten zur Satz- und Wortgrenzenerkennung, schneidet in einem [aktuellen Benchmark](https://github.com/KorAP/Tokenizer-Evaluation#results) vergleichsweise gut ab
;; * guter Kompriss zwischen Unterstützung verschiedener Textsorten und Durchsatz
;; * Spracherkennung (_language detection_) mittels [Lingua](https://github.com/pemistahl/lingua), Hybrid aus statistischen und regelbasierten Verfahren

^{::clerk/visibility {:result :hide}}
(require '[zdl.nlp.tokenizer :as tokenizer])

(def tokenized
  (tokenizer/tokenize sentences))


;; ## Tagging
;;
;; * Ermittlung von Wortklassen, syntaktischen Dependenzrelationen, morphologischen Eigenschaften (Kasus, Genus, Tempus etc.)
;; * Wahl zwischen [spaCy](https://spacy.io/) mit eigens trainierten [Modellen von René](https://huggingface.co/reneknaebel) oder [trankit](https://github.com/nlp-uoregon/trankit)
;; * zudem Eigennamenerkennung (_named entity recognition_) mit [flair](https://github.com/flairNLP/flair) und Lemmatisierung mit [DWDSmor](http://git.zdl.org/zdl/dwdsmor)
;; * in Zukunft: historische Normalisierung (Text+) und Annotation von Beleggüte (EVIDENCE)
;; * Extraktion charakteristischer [Wortverbindungen](https://github.com/zentrum-lexikographie/nlp-pipeline/blob/main/src/zdl/nlp/annotation.clj#L195) (Kollokationen) im Anschluss an Dependenzanalyse
;; * [Python-basierte Pipeline-Komponenten](https://github.com/zentrum-lexikographie/nlp-pipeline/tree/main/zdl) zur Datenerhebung mit einem JVM-basierten „Dirigenten” zwecks Datenintegration

^{::clerk/visibility {:result :hide}}
(require '[zdl.nlp.annotation :as anno]
         '[zdl.nlp.tagger :as tagger :refer [with-tagger]])

#_:clj-kondo/ignore
^{::clerk/visibility {:result :hide}}
(def annotated
  (let [tagger (tagger/combined [(tagger/trankit)
                                 (tagger/flair)
                                 (tagger/simplemma)
                                 (tagger/dwdsmor)])]
    (with-tagger [tagged tagger] tokenized
      (into [] (map anno/process) tagged))))

^{::clerk/visibility {:code :hide :result :hide}}
(require '[zdl.nlp.vis :as vis])

^{::clerk/visibility {:code :hide :result :hide}}
(def show!
  (comp clerk/html vis/svg-str))

^{::clerk/width :full}
(show! (nth annotated 0)) 

;; ## Lemmatisierung
;;
;; * statistische Tagger wie [trankit](https://trankit.readthedocs.io/en/latest/performance.html) und spaCy (noch) nicht hinreichend gut für die Lemmatisierung
;; * insbesondere mit Blick auf die zentrale Rolle dieses NLP-Tasks für lexikographische Zwecke
;; * Kombination aus statistischem Verfahren und lexikon-/automatenbasiertem Verfahren

^{::clerk/viewer (comp clerk/table clerk/use-headers)}
(cons ["Token" "Lemma" "DWDSmor" "Score" "Dep-Rel" "POS" "Numerus" "Genus" "Casus" "Tempus"]
      (for [sentence annotated
            token    (sentence :tokens)
            analysis (or (:dwdsmor token) [{}])
            :let     [{:keys [analysis rank]} analysis]]
        [(:text token) (:lemma token) (or analysis "–") (or rank "–") (:deprel token) (:xpos token)
         (:number token) (:gender token) (:case token) (:tense token)]))


;; ## Indizierung
;;
;; * Suchdienst auf Basis von [Multi Tier Annotation Search (MTAS)](https://github.com/textexploration/mtas), einer Erweiterung für [Apache Solr](https://solr.apache.org/)
;; * Solr liefert Funktionen wie Suche in Metadaten (Datierungen, Subkorpora, bibliographische Angaben, regionale Verteilung), Facettierung über diese Eigenschaften sowie [Skalierbarkeit](https://cwiki.apache.org/confluence/display/solr/PublicServers)
;; * MTAS liefert die **Unterstützung der CL-Domäne: Mehrebenenannotationen** von Texten sowie **domainspezifische Abfragesprache**
;; * ursprünglich entwickelt für [Nederlab](https://www.nederlab.nl/), auch im Einsatz für [historisch orientierte Projekte](https://www.middelnederlands.nl/); nachgenutzt z. B. durch [INCEpTION](https://inception-project.github.io/) (WebAnno-Nachfolger)
;; * Datenaustauschformat mit dem Suchdienst für die Indizierung: [FoLiA](https://proycon.github.io/folia/)

^{::clerk/visibility {:result :hide}}
(require '[zdl.nlp.folia :as folia]
         '[gremid.xml :as gxml]
         '[zdl.nlp.xml :as xml])

(into [] (map-indexed (fn [i s] (folia/sentence (str "s." (inc i)) s))) annotated)

;; * Ergänzung des annotierten Textes um Metadaten

(let [sentences (map-indexed (fn [i s] (folia/sentence (str "s." (inc i)) s))
                             annotated)
      text      [:FoLiA {:xmlns "http://ilk.uvt.nl/folia"}
                 [:text [:p sentences]]]]
  [:doc
   [:field {:name "id"} (str (random-uuid))]
   [:field {:name "timestamp"} (System/currentTimeMillis)]
   [:field {:name "title"} "ChatGPT und Turing"]
   [:field {:name "date"} "2023-06-10T00:00:00Z"]
   [:field {:name "date_range"} "2023-06"]
   [:field {:name "text_type"} "folia"]
   [:field {:name "text"} (xml/xml->str (gxml/sexp->node text))]])

;; ## Retrieval
;;
;; * [CQL](https://www.sketchengine.eu/documentation/corpus-querying/) als Abfragesprache für die Mehrebenenannotationen
;; * _Lucene Standard Query Language_ für die Filterung abzufragender Dokumente, z. B. zur Bildung „virtueller” Subkorpora 

^{::clerk/visibility {:result :hide}}
(require '[zdl.nlp.index :refer [send-mtas-query!]])

(send-mtas-query! "title:chatgpt && date_range:[1900-01 TO 2000-01}"
                  "<ent=\"PER\"/>")

(send-mtas-query! "title:chatgpt && date_range:[2023-01 TO 2024-01}"
                  "<ent=\"PER\"/>")

^{::clerk/visibility {:result :hide}}
(defn kwic->html
  [{{:keys [segments]} :hits}]
  (if-not (seq segments)
    [:p [:em "Keine Ergebnisse!"]]
    [:ol
     (for [{:keys [segment]} segments]
       [:li
        (for [token segment
              :let  [space-after? (nil? (some (comp #{"w.space"} :prefix)
                                                          token))
                                 [text]       (filter (comp #{"t"} :prefix) token)]
              :when text]
          [(if (text :hit?) :strong :span) (str (text :value) (when space-after? " "))])])]))

^{::clerk/visibility {:result :hide}}
(defn query
  [filter-q cql-q]
  (kwic->html (send-mtas-query! filter-q cql-q)))

^{::clerk/viewer clerk/html}
(query "title:chatgpt && date_range:[2023-01 TO 2024-01}"
       "<ent/>")

^{::clerk/viewer clerk/html}
(query "date_range:[2023-01 TO 2024-01}"
       "[t=\".*To?uring.*\"] within <colloc=\"ATTR\"/>")

^{::clerk/viewer clerk/html}
(query "date_range:[2023-01 TO 2024-01}"
       "[t=\"KI\" & gender=\"FEM\"]")

^{::clerk/viewer clerk/html}
(query "date_range:[2023-01 TO 2024-01}"
       "[t=\"KI\" & gender=\"NEUT\"]")

(def annotated
  (let [tagger (tagger/combined [(tagger/trankit)
                                 (tagger/flair)
                                 (tagger/simplemma)
                                 (tagger/dwdsmor)])]
    (with-tagger [tagged tagger]
      (tokenizer/tok)
      (show! (first (map anno/process tagged))))))
