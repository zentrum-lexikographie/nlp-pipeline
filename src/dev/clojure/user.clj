(ns user
  (:require
   [nextjournal.clerk :as clerk]
   [zdl.ddc :as ddc]
   [zdl.nlp.vis :as vis]
   [zdl.nlp :as nlp]))

(def serve-notebooks!
  (partial clerk/serve! {:browse?        true}))

(def build-notebooks!
  (partial clerk/build! {:paths ["notebooks/simplemma_coverage.clj"]}))

(comment
  (serve-notebooks!)
  (build-notebooks!))

(defn nlp
  [sentence]
  (->> (nlp/process-chunks [{:text sentence}])
       (mapcat :sentences)
       (first)))

(def ddc-kern-endpoint
  ["ddc.dwds.de" 52000])

(defn ddc
  ([query]
   (ddc ddc-kern-endpoint query))
  ([endpoint query]
   (->> (ddc/query endpoint query)
        (take 1)
        (nlp/annotate-docs)
        (mapcat :chunks)
        (mapcat :sentences)
        (first))))

(comment
  (vis/show! (ddc "Erleuchtung"))
  (nlp "Es gelang nicht, und 2004 wurde in Berlin der Doktortitel per Gerichtsbeschluß aberkannt."))
