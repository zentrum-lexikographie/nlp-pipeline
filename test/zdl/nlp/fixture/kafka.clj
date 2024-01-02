(ns zdl.nlp.fixture.kafka
  (:require
   [clojure.java.io :as io]
   [gremid.xml :as gxml]
   [zdl.nlp.tei :as tei]))

(def tei-corpora
  (delay
    (->> ["kafka_hungerkuenstler_1922" "kafka_prozess_1925"]
         (map #(io/resource (format "zdl/nlp/fixture/kafka/%s.TEI-P5.xml" %)))
         (mapcat gxml/read-events)
         (tei/normalize-space)
         (tei/segment))))

(defn texts
  []
  (map :text (filter map? @tei-corpora)))
