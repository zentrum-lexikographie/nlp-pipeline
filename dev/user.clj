(ns user
  (:require [nextjournal.clerk :as clerk]
            [clojure.string :as str]))

(comment
  (clerk/serve! {:watch-paths    ["notebooks" "src"]
                 :show-filter-fn #(str/starts-with? % "notebooks")
                 :browse?        true})
  (clerk/show! "notebooks/presentation.clj")
  (clerk/build! {:paths ["notebooks/presentation.clj"]}))

