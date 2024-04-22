(ns zdl.ddc-test
  (:require
   [clojure.test :refer [deftest is]]
   [taoensso.timbre :as log]
   [zdl.ddc.corpora :as ddc.corpora]))

(def corpora
  #{"dtak_www" "kern_www" "bz_www" "bundestag_www"})

(def endpoints
  (into [] (map @ddc.corpora/endpoints) corpora))

(defn query-corpora
  [& args]
  (apply ddc.corpora/query corpora args))

(defn total-hits
  [total _hit]
  (inc total))

(def sample-query
  "sein #desc_by_date #separate")

(deftest parallel-corpus-query
  (binding [ddc.corpora/*num-results-per-corpus* 10]
    (log/with-min-level :info
      (as-> (query-corpora sample-query) $
        (is (<= 40 (count $)))))))
