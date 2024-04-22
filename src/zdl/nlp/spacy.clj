(ns zdl.nlp.spacy
  (:require [zdl.env :as env]
            [zdl.util :refer [assoc*]]
            [clojure.string :as str]
            [zdl.schema :as schema]
            [taoensso.timbre :as log]))

(require '[zdl.python]
         '[libpython-clj2.python :as py]
         '[libpython-clj2.require :refer [require-python]])

(require-python 'spacy 'spacy.tokens)

(defonce dep-tagger
  (delay
    (when env/spacy-dep-model
      #_:clj-kondo/ignore
      (let [model (spacy/load env/spacy-dep-model)]
        (log/infof "Loaded spaCy dependency tagger model '%s'"
                   env/spacy-dep-model)
        model))))

(defonce ner-tagger
  (delay
    (when env/spacy-ner-model
      (let [model (spacy/load env/spacy-ner-model)]
        (log/infof "Loaded spaCy NER tagger model '%s'"
                   env/spacy-ner-model)
        model))))

(defn segment->doc
  [tagger {:keys [sentences]}]
  (when tagger
    (let [tokens (mapcat :tokens sentences)]
      #_:clj-kondo/ignore
      (spacy.tokens/Doc
       (py/py.- tagger "vocab")
       :words (py/->py-list (map :form tokens))
       :spaces (py/->py-list (map :space-after? tokens))
       :sent_starts (py/->py-list (map #(zero? (:n %)) tokens))))))

(defn pipe
  [tagger docs]
  (if tagger
    (py/py. tagger "pipe" docs
            :batch_size env/spacy-batch-size
            :n_process env/spacy-parallel)
    (repeat nil)))

(defn normalize-punct
  [s]
  (if (some-> s (str/starts-with? "$")) "PUNCT" s))

(defn sent-n
  [s-start n]
  (- n s-start))

(defn t-attr
  [t k]
  (py/get-attr t k))

(defn m-attr
  [morph k]
  (->> k (py/py. morph "get") (first) (schema/tag-str)))

(defn merge-dep-annotations
  [{:keys [sentences] :as segment} dep-doc]
  (->>
   (for [[sentence s] (map list sentences (py/py.- dep-doc "sents"))]
     (let [sent-n (partial sent-n (py/py.- s "start"))]
       (->>
        (for [[token t] (map list (sentence :tokens) s)]
          (let [t-attr  (partial t-attr t)
                t-tag   (comp schema/tag-str normalize-punct t-attr)
                m-attr  (partial m-attr (py/py.- t "morph"))
                deprel  (t-tag "dep_")
                head    (when (not= deprel "ROOT")
                         (sent-n (py/py.- (t-attr "head") "i")))
                wordvec (when (t-attr "has_vector") (t-attr "vector"))]
            (-> token
                (assoc* :deprel deprel)
                (assoc* :head   head)
                (assoc* :wordvec wordvec)
                (assoc* :lemma  (t-attr "lemma_"))
                (assoc* :oov?   (t-attr "is_oov"))
                (assoc* :upos   (t-tag "pos_"))
                (assoc* :xpos   (t-tag "tag_"))
                (assoc* :number (m-attr "Number"))
                (assoc* :gender (m-attr "Gender"))
                (assoc* :case   (m-attr "Case"))
                (assoc* :tense  (m-attr "Tense"))
                (assoc* :person (m-attr "Person"))
                (assoc* :mood   (m-attr "Mood"))
                (assoc* :degree (m-attr "Degree")))))
        (vec) (assoc sentence :tokens))))
   (vec) (assoc segment :sentences)))

(defn merge-ner-annotations
  [{:keys [sentences] :as segment} ner-doc]
  (->>
   (for [[sentence s] (map list sentences (py/py.- ner-doc "sents"))]
     (let [sent-n (partial sent-n (py/py.- s "start"))]
       (->>
        (for [entity (py/py.- s "ents")]
          {:type    :entity
           :label   (-> entity (py/py.- "label_") schema/tag-str)
           :targets (->> (range (sent-n  (py/py.- entity "start"))
                                (sent-n  (py/py.- entity "end")))
                         (into []))})
        (vec) (not-empty) (assoc* sentence :spans))))
   (vec) (assoc segment :sentences)))

(defn merge-annotations
  [segment dep-doc ner-doc]
  (cond-> segment
    dep-doc (merge-dep-annotations dep-doc)
    ner-doc (merge-ner-annotations ner-doc)))

(defn tag
  [segments]
  (let [dep-tagger @dep-tagger
        ner-tagger @ner-tagger
        dep-docs   (map (partial segment->doc dep-tagger) segments)
        ner-docs   (map (partial segment->doc ner-tagger) segments)
        dep-docs   (pipe dep-tagger dep-docs)
        ner-docs   (pipe ner-tagger ner-docs)]
    (pmap merge-annotations segments dep-docs ner-docs)))
