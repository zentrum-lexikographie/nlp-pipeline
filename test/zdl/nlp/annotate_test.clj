(ns zdl.nlp.annotate-test
  (:require
   [clojure.test :refer [deftest is]]
   [zdl.nlp.annotate :refer [annotate]]
   [zdl.nlp.fixture.kafka :as kafka]
   [zdl.nlp.spacy :as spacy]
   [zdl.nlp.tokenizer :as tokenizer]
   [zdl.nlp.vis :as vis]))

(defn annotate-kafka
  []
  (->> (kafka/texts) (random-sample 0.1) (take 10)
       (map tokenizer/tokenize) (spacy/tag) (pmap annotate)))

(deftest annotations
  (is (sequential? (doall (annotate-kafka)))))

(comment
  (->>
   (annotate-kafka)
   (mapcat :sentences)
   (rand-nth)
   (vis/show!)))
