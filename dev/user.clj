(ns user
  (:require [nextjournal.clerk :as clerk]))

(def serve-notebooks!
  (partial clerk/serve! {:browse?        true}))

(def build-notebooks!
  (partial clerk/build! {:paths ["notebooks/simplemma_coverage.clj"]}))

(comment
  (serve-notebooks!)
  (build-notebooks!))
