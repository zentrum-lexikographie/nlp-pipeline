(ns user
  (:require
   [nextjournal.clerk :as clerk]
   [zdl.nlp]
   [zdl.nlp.vis :as vis]))

(def serve-notebooks!
  (partial clerk/serve! {:browse?        true}))

(def build-notebooks!
  (partial clerk/build! {:paths ["notebooks/simplemma_coverage.clj"]}))

(comment
  (serve-notebooks!)
  (build-notebooks!))

(defn nlp
  [sentence]
  (->> (zdl.nlp/process-chunks [{:text sentence}])
       (mapcat :sentences)
       (first)))

(def visualize
  (comp vis/show! nlp))

(comment
  (visualize "Es gelang nicht, und 2004 wurde in Berlin der Doktortitel per Gerichtsbeschluß aberkannt."))
