(ns zdl.nlp.vis-test
  (:require
   [clojure.test :refer [deftest is]]
   [zdl.nlp :refer [process-docs]]
   [zdl.nlp.fixture.kafka :as kafka]
   [zdl.nlp.vis :as vis]))

(defn annotate-kafka
  []
  (process-docs (kafka/docs)))

(deftest annotations
  (is (sequential? (doall (annotate-kafka)))))

(defn show-kafka-sentence!
  []
  (->>
   (annotate-kafka)
   (mapcat :chunks)
   (mapcat :sentences)
   (rand-nth)
   (vis/show!)))

(comment (show-kafka-sentence!))
