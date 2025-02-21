(ns zdl.nlp.dwdsmor.coverage
  (:gen-class)
  (:require [clojure.java.io :as io]
            [zdl.conllu :as conllu]
            [taoensso.timbre :as log]
            [zdl.log]
            [zdl.nlp]
            [zdl.nlp.dwdsmor :as dwdsmor]
            [clojure.data.csv :as csv]))

(defn conll-source?
  [^java.io.File f]
  (and (.isFile f) (.. f (getName) (endsWith ".conll"))))

(def args->files-xf
  (comp (map io/file)
        (mapcat file-seq)
        (filter conll-source?)))

(defn files->lines
  ([files]
   (files->lines files nil))
  ([files prev-reader]
   (when prev-reader (.close ^java.io.Reader prev-reader))
   (when-let [file (first files)]
     (log/infof "Reading %s" (str file))
     (let [r (io/reader file)]
       (lazy-cat (line-seq r) [""] (files->lines (rest files) r))))))


(def dwdsmor-lookup
  (memoize (fn [s] (some? (seq (dwdsmor/lookup s))))))

(defn lemmatize
  [c]
  (let [c (conllu/parse-chunk c)]
    (->>
     (into []
           (map #(assoc % ::dwdsmor? (dwdsmor-lookup (:form %))))
           (mapcat :tokens (c :sentences)))
     (assoc c ::lemmatized))))

(def inc*
  (fnil inc 0))

(defn spit-coverage?
  [{:keys [tokens]}]
  (and tokens (pos? tokens) (zero? (mod tokens 1000000))))

(defn spit-coverage!
  [{:keys [tokens] :as coverage}]
  (log/infof "Writing coverage report after %,d tokens" tokens)
  (with-open [w (io/writer "dwdsmor-coverage.edn")]
    (binding [*print-length*   nil
              *print-dup*      nil
              *print-level*    nil
              *print-readably* true
              *out*            w]
      (pr coverage)))
  coverage)

(defn valid-form?
  [s]
  (nil? (re-seq #"[\p{Digit}\p{Punct}&&[^\-]]" s)))

(defn capitalized?
  [s]
  (re-seq #"^[\p{Upper}ÄÖÜß]+$" s))

(defn report?
  [form]
  (and (some-> form valid-form?) (not (capitalized? form))))

(defn update-coverage
  [coverage {:keys [form xpos] ::keys [dwdsmor?]}]
  (-> coverage
      (update :tokens inc*)
      (update-in [:coverage xpos dwdsmor?] inc*)
      (cond-> (and (not dwdsmor?) (report? form))
        (update-in [:uncovered xpos form] inc*))
      (cond-> (spit-coverage? coverage) (spit-coverage!))))


(defn -main
  [& args]
  (zdl.log/configure! false)
  (try
    (when-not @dwdsmor/transducer
      (throw (ex-info "No DWDSmor automaton given" {})))
    (let [files  (sort (into [] args->files-xf args))
          lines  (files->lines files)
          chunks (sequence conllu/lines->chunks-xf lines)
          chunks (pmap lemmatize chunks)
          chunks (sequence zdl.log/throughput-xf chunks)]
      (->> (mapcat ::lemmatized chunks)
           (reduce update-coverage {})
           (spit-coverage!)))
    (finally
      (shutdown-agents))))

(comment
  (do
    (defonce coverage
      (read-string (slurp (io/file "data/dwdsmor-coverage.edn"))))
    (->>
     (for [[_pos stats] (:uncovered coverage) [form n] stats :when form] [form n])
     (reduce (fn [m [form n]] (update m form (fnil + 0) n)) {})
     (sort-by (comp - second))
     (csv/write-csv out)
     (with-open [out (io/writer (io/file "data" "dwdsmor-not-covered.csv"))]))
    (->>
     (for [[pos cov] (:coverage coverage)] [pos (cov true) (cov false)])
     (sort)
     (csv/write-csv out)
     (with-open [out (io/writer (io/file "data" "dwdsmor-coverage.csv"))]))))
