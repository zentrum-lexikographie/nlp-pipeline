(ns zdl.ddc-test
  (:require
   [clojure.core.async :as a]
   [clojure.test :refer [deftest is]]
   [taoensso.timbre :as log]
   [zdl.ddc :as ddc]
   [zdl.ddc.corpora :as ddc.corpora]))

(def corpora
  ["dtak_www" "kern_www" "bz_www" "bundestag_www"])

(def endpoints
  (into [] (map @ddc.corpora/endpoints) corpora))

(defn query-corpora
  [& args]
  (a/merge (map (fn [[host port]] (apply ddc/query-results->ch host port args))
                endpoints)
           (* 2 (count corpora))))

(defn total-hits
  [total _hit]
  (inc total))

(def sample-query
  "sein #desc_by_date #separate")

(deftest parallel-corpus-query
  (log/with-min-level :info
    (as-> (query-corpora sample-query  :page-size 100) $
      (a/pipe $ (a/chan 1 (take 1000)))
      (a/into [] $)
      (a/<!! $)
      (is (= 1000 (count $))))))
