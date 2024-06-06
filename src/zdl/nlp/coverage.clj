(ns zdl.nlp.coverage
  (:gen-class)
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [taoensso.timbre :as log]
   [zdl.conllu :as conllu]
   [zdl.log]
   [zdl.nlp.simplemma :as simplemma]
   [zdl.util :refer [spit-edn]]))

(defn files->lines
  ([files]
   (files->lines files nil))
  ([files prev-reader]
   (when prev-reader (.close ^java.io.Reader prev-reader))
   (when-let [file (first files)]
     (log/infof "Reading %s" (str file))
     (let [r (io/reader (fs/file file))]
       (lazy-cat (line-seq r) [""] (files->lines (rest files) r))))))

(defn token-covered?
  [{:keys [form]}]
  (some? (simplemma/lookup form)))

(defn annotate
  [c]
  (->>
   (into []
         (map #(assoc % ::covered? (token-covered? %)))
         (mapcat :tokens (c :sentences)))
   (assoc c ::coverage)))

(defn valid-form?
  [s]
  (nil? (re-seq #"[\p{Digit}\p{Punct}&&[^\-]]" s)))

(defn capitalized?
  [s]
  (re-seq #"^[\p{Upper}ÄÖÜß]+$" s))

(defn report?
  [form]
  (and (some-> form valid-form?) (not (capitalized? form))))

(def inc*
  (fnil inc 0))

(defn update-coverage
  [coverage {:keys [form xpos] ::keys [covered?]}]
  (-> coverage
      (update :tokens inc*)
      (update-in [:coverage xpos covered?] inc*)
      (cond-> (and (not covered?) (report? form))
        (update-in [:uncovered xpos form] inc*))))

(defn conll-files
  [root]
  (mapcat (partial fs/glob root) ["*.conll" "**/*.conll"]))

(defn -main
  [n & args]
  (zdl.log/configure! false)
  (try
    (let [files  (mapcat conll-files args)
          files  (dedupe (sort files))
          lines  (files->lines files)
          chunks (sequence conllu/lines->chunks-xf lines)
          chunks (take (parse-long n) chunks)
          chunks (pmap (comp annotate conllu/parse-chunk) chunks)
          chunks (sequence zdl.log/throughput-xf chunks)]
      (->> (mapcat ::coverage chunks)
           (reduce update-coverage {})
           (spit-edn (fs/file "coverage.edn"))))
    (finally
      (shutdown-agents))))
