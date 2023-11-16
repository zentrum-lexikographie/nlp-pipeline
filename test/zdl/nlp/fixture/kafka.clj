(ns zdl.nlp.fixture.kafka
  (:require
   [clojure.java.io :as io]
   [gremid.xml :as gxml]))

(defn documents
  []
  (->> ["kafka_hungerkuenstler_1922" "kafka_prozess_1925"]
       (map #(io/resource (format "zdl/nlp/fixture/kafka/%s.TEI-P5.xml" %)))
       (into [] (map (comp gxml/events->node gxml/read-events)))))

(defn texts
  []
  (let [texts (mapcat (partial gxml/elements :text) (documents))]
    (into [] (map gxml/text) texts)))

(comment
  (map count (texts)))
