(ns user
  (:require [nextjournal.clerk :as clerk]
            [clojure.string :as str]))

(def serve-notebooks!
  (partial clerk/serve! {:watch-paths    ["notebooks" "src"]
                         :show-filter-fn #(str/starts-with? % "notebooks")
                         :browse?        true}))

(def build-notebooks!
  (partial clerk/build! {:paths ["notebooks/dwdsmor_coverage.clj"]}))
