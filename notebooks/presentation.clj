^{:nextjournal.clerk/visibility {:code :hide} :nextjournal.clerk/toc true}
(ns presentation
  (:require
   [nextjournal.clerk :as clerk]))

;; # NLP-Annotationswerkzeuge für das ZDL
;;
;; _Eigenentwicklungen sowie Komponenten „von der Stange”_

;; Wir testen die Pipeline anhand einer Frage aus
;; einem [FAZ-Interview](https://www.faz.net/aktuell/wirtschaft/unternehmen/hans-und-jakob-uszkoreit-wurde-chatgpt-zu-frueh-auf-die-menschheit-losgelassen-18949623.html?premium=0x0dcb06b0a3b68c815d3ba0f735ed6813dfaed6e3be144c896be8d63e2019584d)
;; mit Hans und Jakob Uszkoreit sowie einer fiktiven Antwort.

^{::clerk/visibility {:result :hide}}
(def sentences
  (str "FAZ: Wenn der Erfinder des berühmten Turing-Tests, Alan Turing, "
       "diese Modelle sehen würde, würde er aber doch sagen: "
       "Das ist eine Intelligenz. Die KI besteht ja den Test. "
       "Alan Turing: I would have failed that test!"))

;; ## Segmentierung
;;
;; Zunächst erfolgt die Satz-/Wortsegmentierung sowie die Ermittlung der
;; Sprache je Satz:

^{::clerk/visibility {:result :hide}}
(require '[zdl.nlp.tokenizer :as tokenizer])

(def tokenized
  (tokenizer/tokenize sentences))

;; Für die Segmentierung
;; wird [KorAP-Tokenizer](https://github.com/KorAP/KorAP-Tokenizer) verwendet.
;; Basierend auf einem deterministischen, endlichen Automaten zur Satz- und
;; Wortgrenzenerkennung, schneidet dieser Tokenizer in einem [aktuellen
;; Benchmark](https://github.com/KorAP/Tokenizer-Evaluation#results)
;; vergleichsweise gut ab.

;; ## Tagging
;;
;; (Wortklassen, Syntaxanalyse, Morphologie)

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

^{::clerk/visibility {:result :hide}}
(require '[zdl.nlp.vis :as vis])

^{::clerk/visibility {:result :hide}}
(def show!
  (comp clerk/html vis/svg-str))

^{::clerk/width :full}
(show! (nth annotated 0)) 

^{::clerk/width :wide}
(show! (nth annotated 1))

^{::clerk/width :wide}
(show! (nth annotated 0))
