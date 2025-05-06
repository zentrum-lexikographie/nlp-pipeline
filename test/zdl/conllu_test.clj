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
    (doseq [c (conllu/parse (line-seq r))]
      (is (schema/valid-chunk? c) (m/explain schema/Chunk c)))))
