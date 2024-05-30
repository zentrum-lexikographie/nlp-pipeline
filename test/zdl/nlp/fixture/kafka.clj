(ns zdl.nlp.fixture.kafka
  (:require
   [clojure.java.io :as io]
   [gremid.xml :as gxml]
   [zdl.xml.tei :as tei]))

(defn tei->doc
  [k]
  (with-open [input (-> (format "zdl/nlp/fixture/kafka/%s.TEI-P5.xml" k)
                        (io/resource)
                        (io/input-stream))]
    (-> input gxml/read-events tei/normalize-space tei/events->doc
        (assoc :collection "dta" :file k))))

(defn docs
  []
  (map tei->doc ["kafka_hungerkuenstler_1922" "kafka_prozess_1925"]))
