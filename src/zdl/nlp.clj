(ns zdl.nlp
  (:require
   [zdl.log]
   [zdl.nlp.deps :as deps]
   [zdl.nlp.dwdsmor :as dwdsmor]
   [zdl.nlp.gdex :as gdex]
   [zdl.nlp.hash :as hash]
   [zdl.nlp.langid :as langid]
   [zdl.nlp.spacy :as spacy]
   [zdl.nlp.tokenizer :as tokenizer]))

(defn unfold-layer
  [k i v]
  (for [c (v k)]  (assoc c ::parent i)))

(defn fold-layer
  [k parent contents]
  (assoc parent k (into [] (map #(dissoc % ::parent)) contents)))

(defn annotate-layer
  [k annotate-fn coll]
  (->> coll
       (map-indexed (partial unfold-layer k))
       (mapcat identity)
       (annotate-fn)
       (partition-by ::parent)
       (map (partial fold-layer k) coll)))

(def annotate-tokens
  (partial annotate-layer :tokens (partial map dwdsmor/lemmatize)))

(def annotate-sentences
  (partial
   annotate-layer
   :sentences
   (comp (partial pmap (comp deps/analyze-collocations gdex/score))
         annotate-tokens)))

(def annotate-chunks
  (comp
   (partial sequence zdl.log/throughput-xf)
   (partial pmap (comp langid/detect-lang hash/fingerprint))
   annotate-sentences
   spacy/tagged-seq))

(def annotate-docs
  (partial annotate-layer :chunks annotate-chunks))

(defn tokenize-chunk
  [{:keys [text sentences] :as chunk}]
  (cond
    sentences chunk
    text      (merge (tokenizer/tokenize text) chunk)
    :else     (throw (IllegalArgumentException. (str chunk)))))

(def tokenize-chunks
  (partial map tokenize-chunk))

(def process-chunks
  (comp annotate-chunks tokenize-chunks))

(def tokenize-docs
  (partial annotate-layer :chunks tokenize-chunks))

(def process-docs
  (comp annotate-docs tokenize-docs))
