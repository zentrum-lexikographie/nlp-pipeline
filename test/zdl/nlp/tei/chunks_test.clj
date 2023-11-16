(ns zdl.nlp.tei.chunks-test
  (:require
   [clojure.test :refer [deftest is]]
   [zdl.nlp.fixture.kafka :as kafka]
   [zdl.nlp.tei.chunks :as tei.chunks]
   [gremid.xml :as gxml]))

(deftest parse-into-chunks
  (let [events (mapcat gxml/node->events (kafka/documents))
        chunks (sequence tei.chunks/->chunks events)
        texts  (map :text (filter :text chunks))]
    (is (every? string? texts))))

