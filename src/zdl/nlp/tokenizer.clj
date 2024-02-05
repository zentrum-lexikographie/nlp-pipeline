(ns zdl.nlp.tokenizer
  "Segmentation and tokenization."
  (:import
   (de.ids_mannheim.korap.tokenizer DerekoDfaTokenizer_de)
   (opennlp.tools.util Span))
  (:require [zdl.schema :as schema]))

(def ^DerekoDfaTokenizer_de tokenizer
  (DerekoDfaTokenizer_de.))

(defn span->map
  "Turn OpenNLP Span objects into maps."
  ([k s span]
   (span->map k 0 s span))
  ([k offset s ^Span span]
   (let [start (+ offset (.getStart span))
         end   (+ offset (.getEnd span))]
     {k      (subs s start end)
      :start start
      :end   end})))

(defn segment-sentences
  [s]
  (map (partial span->map :text s) (.sentPosDetect tokenizer s)))

(def index-tokens-xf
  (map-indexed #(assoc %2 :n %1)))

(defn segment-tokens
  [s {:keys [start text] :as sentence}]
  (assoc sentence :tokens
         (into []
               (comp (map (partial span->map :form start s)) index-tokens-xf)
               (.tokenizePos tokenizer text))))

(defn assoc-space-after**
  [[{:keys [end] :as token} next-token]]
  (assoc token :space-after? (< end (get next-token :start end))))

(defn assoc-space-after*
  [[{:keys [tokens] :as sentence} {next-tokens :tokens}]]
  (let [tokens      (concat tokens (take 1 next-tokens))
        token-pairs (partition-all 2 1 tokens)
        tokens      (map assoc-space-after** token-pairs)
        tokens      (cond-> tokens (seq next-tokens) (butlast))]
    (assoc sentence :tokens (vec tokens))))

(defn assoc-space-after
  [sentences]
  (let [sentence-pairs (partition-all 2 1 sentences)]
    (map assoc-space-after* sentence-pairs)))

(defn tokenize
  [s]
  {:text s
   :sentences (vec (assoc-space-after (map (partial segment-tokens s)
                                           (segment-sentences s))))})

(comment
  (every?
   schema/valid-sentence?
   (:sentences (tokenize "Das ist ein erster Test. Das ist ein zweiter Satz!"))))
