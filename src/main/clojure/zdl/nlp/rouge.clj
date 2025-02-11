(ns zdl.nlp.rouge
  (:require [clojure.java.io :as io]
            [clojure.set :refer [intersection]]
            [clojure.string :as str]
            [clojure.data.csv :as csv]
            [zdl.nlp.annotate :refer [annotate]]
            [zdl.nlp.tokenizer :as tokenizer]))

(defonce equivs
  (with-open [is (io/input-stream (io/file "data" "germanet.edn.gz"))
              is (java.util.zip.GZIPInputStream. is)
              r  (io/reader is)]
    (let [germanet  (read-string (slurp r))
          synsets   (reduce #(assoc %1 (:id %2) (into #{} (:forms %2)))
                            {} (:synsets germanet))
          relations (map #(into #{} (concat (synsets (:from %))
                                            (synsets (:to %))))
                         (:relations germanet))]
      (reduce (fn [m equivs]
                (reduce #(update %1 %2 (fnil into (sorted-set)) (disj equivs %2))
                        m equivs))
              (sorted-map)
              (concat (vals synsets) relations)))))

(defn gloss->lemmata
  [s]
  (let [annotated (->> s tokenizer/tokenize vector annotate)
        tokens    (mapcat :tokens (:sentences annotated))
        tokens    (remove (comp #{"ADP" "AUX" "CCONJ" "SCONJ" "DET" "PART" "PRON" "PUNCT"} :upos) tokens)
        lemmata   (map (some-fn :lemma :form) tokens)]
    (into (sorted-set) (remove #(re-find #"\d" %)) lemmata)))

(defn expand-lemma
  [lemma]
  (into (sorted-set lemma) (equivs lemma)))

(def lex-gpt-samples
  (read-string (slurp (io/file ".." "lex" "sample-gpt.edn"))))

(defn overlap
  [dwds gpt]
  (reduce + (for [s dwds] (if (seq (intersection (expand-lemma s) gpt)) 1 0))))

(defn lex-gpt->csv
  [{glosses-dwds :glosses {gloss-gpt :message} :gpt
    {:keys [lexeme tranche total journalism web]} :sample}]
  (let [glosses-dwds (str/join "; " glosses-dwds)
        gloss-gpt    (str/replace gloss-gpt #"\s+" " ")
        lemmata-dwds (gloss->lemmata glosses-dwds)
        lemmata-gpt  (gloss->lemmata gloss-gpt)
        matches      (overlap lemmata-dwds lemmata-gpt)
        matches?     (pos? matches)
        precision    (when matches?
                       (float (/ matches (count lemmata-gpt))))
        recall       (when matches?
                       (float (/ matches (count lemmata-dwds))))
        f-score      (when matches?
                       (float (* 2 (/ (* precision recall) (+ precision recall)))))]
    [tranche total journalism web lexeme recall precision f-score gloss-gpt glosses-dwds]))

(comment
  (equivs "zahlen")
  (count lex-gpt-samples)
  (with-open [w (io/writer "lex-gpt-eval.csv")]
    (->> lex-gpt-samples
         (map lex-gpt->csv)
         (csv/write-csv w))))
