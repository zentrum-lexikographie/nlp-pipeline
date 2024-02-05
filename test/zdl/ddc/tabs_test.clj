(ns zdl.ddc.tabs-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is]]
   [malli.core :as m]
   [zdl.ddc.tabs :as ddc.tabs]
   [zdl.schema :as schema]))

(def sample
  (io/resource "zdl/ddc/sample.tabs"))

(deftest parse-sample
  (with-open [r (io/reader sample)]
    (doseq [s (ddc.tabs/parse (line-seq r))]
      (is (schema/valid-sentence? s) (m/explain schema/Sentence s)))))
