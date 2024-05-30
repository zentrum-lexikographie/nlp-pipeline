(ns zdl.ddc-test
  (:require
   [clojure.test :refer [deftest is]]
   [taoensso.timbre :as log]
   [zdl.ddc.corpora :as ddc.corpora]))

(def corpora
  #{"dtak_www" "kern_www" "bz_www" "bundestag_www"})

(defn query-corpora
  [& args]
  (binding [ddc.corpora/*queried* corpora]
    (apply ddc.corpora/query args)))

(def sample-query
  "sein #desc_by_date #separate")

(deftest parallel-corpus-query
  (binding [ddc.corpora/*num-results-per-corpus* 10]
    (log/with-min-level :info
      (as-> (query-corpora sample-query) $
        (is (<= 40 (count $)))))))
