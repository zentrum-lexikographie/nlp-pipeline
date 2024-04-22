(ns zdl.nlp.fixture.kafka
  (:require
   [clojure.java.io :as io]
   [gremid.xml :as gxml]
   [zdl.xml.tei :as tei]))

(def tei-corpora
  (delay
    (->> ["kafka_hungerkuenstler_1922" "kafka_prozess_1925"]
         (map #(io/resource (format "zdl/nlp/fixture/kafka/%s.TEI-P5.xml" %)))
         (mapcat gxml/read-events)
         (tei/normalize-space)
         (tei/chunks))))

(defn texts
  []
  (map :text (filter map? @tei-corpora)))

(comment
  (rand-nth @tei-corpora)
  (rand-nth (texts)))
