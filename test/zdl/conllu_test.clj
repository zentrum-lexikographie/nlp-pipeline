(ns zdl.conllu-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is]]
   [malli.core :as m]
   [zdl.conllu :as conllu]
   [zdl.schema :as schema]))

(def sample
  (io/resource "zdl/sample.conll"))

(deftest parse-sample
  (with-open [r (io/reader sample)]
    (doseq [s (conllu/parse (line-seq r))]
      (is (schema/valid-sentence? s) (m/explain schema/Sentence s)))))
