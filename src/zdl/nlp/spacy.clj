(ns zdl.nlp.spacy
  (:require
   [clojure.string :as str]
   [libpython-clj2.python :as py]
   [taoensso.timbre :as log]
   [zdl.env :as env]
   [zdl.python :as python]
   [zdl.schema :refer [tag-str]]
   [zdl.util :refer [assoc*]]
   [clojure.java.io :as io]))

(when-not (python/installed? "spacy")
  (log/infof "Initializing spaCy")
  (python/install-reqs! (io/resource "zdl/nlp/spacy.requirements.txt"))
  (python/python! "-m spacy download de_core_news_sm"))

(require '[libpython-clj2.require :refer [require-python]])
(require-python 'spacy 'spacy.tokens 'thinc.api)

#_:clj-kondo/ignore
(when false #_env/spacy-gpu?
  (thinc.api/set_gpu_allocator "pytorch")
  (some-> env/spacy-gpu-id (thinc.api/require_cpu)))

#_:clj-kondo/ignore
(def model
  (spacy/load "de_core_news_sm"))

(def pos-tag-str
  (memoize (fn [s] (if (str/starts-with? s "$") "PUNCT" (tag-str s)))))

(def helper-fns
  (-> (slurp (io/resource "zdl/nlp/spacy/util.py"))
      (py/run-simple-string)
      (get :globals)))

(def token-data
  (comp py/->jvm (get helper-fns "token_data")))

(def entity-data
  (comp py/->jvm (get helper-fns "entity_data")))

(defn morph->dict
  [morph]
  (when-let [features (some-> morph (str/split #"\|"))]
    (->>
     (for [[k vs] (map #(str/split % #"=" 2) features) :when (not-empty vs)]
       [k (vec (str/split vs #","))])
     (into {}))))

(defn merge-token-annotations
  [token t]
  (let [dep?  (not= "ROOT" (t "dep"))
        morph* (some-> (t "morph") morph->dict)
        morph  (comp first morph*)]
    (-> token
        (assoc* :deprel     (when dep? (some-> (t "dep") tag-str)))
        (assoc* :head       (when dep? (t "head")))
        (assoc* :lemma      (t "lemma"))
        (assoc* :upos       (some-> (t "pos") pos-tag-str))
        (assoc* :xpos       (some-> (t "tag") pos-tag-str))
        (assoc* :number     (morph "Number"))
        (assoc* :gender     (morph "Gender"))
        (assoc* :case       (morph "Case"))
        (assoc* :tense      (morph "Tense"))
        (assoc* :person     (morph "Person"))
        (assoc* :mood       (morph "Mood"))
        (assoc* :degree     (morph "Degree"))
        (assoc* :definite   (morph "Definite"))
        (assoc* :verb-form  (morph "VerbForm"))
        (assoc* :verb-type  (morph* "VerbType"))
        (assoc* :punct-type (morph* "PunctType"))
        (assoc* :conj-type  (morph* "ConjType"))
        (assoc* :part-type  (morph* "PartType"))
        (assoc* :pron-type  (morph* "PronType"))
        (assoc* :adp-type   (morph* "AdpType")))))

(defn entity->span
  [[{ss :start} :as tokens] e]
  (let [es      (+ ss (e "start"))
        ee      (+ ss (e "end"))
        covers? (fn [{:keys [start end]}] (<= es start end ee))]
    {:type    :entity
     :label   (tag-str (e "label"))
     :targets (into [] (comp (filter covers?) (map :n)) tokens)}))

(defn merge-annotations
  [{ts :tokens :as s} doc]
  (let [tokens       (into [] (map merge-token-annotations ts (doc "tokens")))
        entity->span (partial entity->span tokens)
        entities     (into [] (map entity->span) (doc "ents"))]
    (cond-> s
      (seq tokens)   (assoc :tokens tokens)
      (seq entities) (assoc :spans  entities))))

#_:clj-kondo/ignore
(defn sentence->doc
  [{:keys [tokens]}]
  (spacy.tokens/Doc
   (py/py.- model vocab)
   :words       (into [] (map :form) tokens)
   :spaces      (into [] (map :space-after?) tokens)
   :sent_starts (if (seq tokens)
                  (into [true] (repeat (dec (count tokens)) false))
                  [])))

#_:clj-kondo/ignore
(defn doc->jvm
  [doc]
  (py/->jvm (py/py. doc to_json)))

(defn pipe
  [docs]
  (map doc->jvm (py/py. model pipe docs :batch_size env/spacy-batch-size)))

(defn annotate-batch
  [sentences]
  (py/with-gil-stack-rc-context
    (let [docs (pipe (map sentence->doc sentences))]
      (vec (pmap merge-annotations sentences docs)))))

(def batch-size
  (* env/spacy-batch-size 16))

(defn annotate
  [sentences]
  (mapcat annotate-batch (partition-all batch-size sentences)))
