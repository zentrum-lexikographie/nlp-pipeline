(ns zdl.xml.tei.corpus.jsonl
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [gremid.xml :as gxml]
   [jsonista.core :as json]
   [zdl.nlp.tokenizer :as tokenizer]
   [zdl.xml.tei :as tei])
  (:import
   (java.util.zip GZIPInputStream)))

(defn tei-element?
  [event]
  (and (tei/start-element? event) (= "TEI" (tei/local-name event))))

(defn tei-header-element?
  [event]
  (and (tei/start-element? event) (= "teiHeader" (tei/local-name event))))

(defn docs
  [events]
  (->>
   events
   (gxml/events->subtrees identity tei-element?)
   (filter (every-pred vector? (comp tei-element? first)))))

(defn metadata
  [events]
  (->>
   events
   (gxml/events->subtrees tei-header-element?)
   (filter :tag)
   (first)))

(defn text
  [events]
  (->> events tei/chunks (filter :text) (map :text)
       (map (comp not-empty str/trim)) (remove nil?) (str/join "\n\n")))

(defn doc->clj
  [events]
  (let [text (text events)]
    {:metadata (metadata events) 
     :text     text
     :tokens   (count (mapcat :tokens (:sentences (tokenizer/tokenize text))))}))

(defn -main
  [& _]
  (with-open [input  (io/input-stream "/home/gregor/ballsport.tei.xml.gz")
              input  (GZIPInputStream. input)]
    (let [docs  (pmap doc->clj (docs (gxml/read-events input)))]
      (into [] (take 2) docs))))
