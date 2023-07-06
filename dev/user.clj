(ns user
  (:require [nextjournal.clerk :as clerk]
            [clojure.string :as str]))

(def serve!
  (partial clerk/serve! {:watch-paths    ["notebooks" "src"]
                         :show-filter-fn #(str/starts-with? % "notebooks")
                         :browse?        true}))

(def show!
  (partial clerk/show! "notebooks/presentation.clj"))

(def build!
  (partial clerk/build! {:paths ["notebooks/presentation.clj"]}))
