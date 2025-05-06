(ns main.clojure.zdl.ddc.query-log
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import
   (java.time LocalDate LocalDateTime)
   (java.time.format DateTimeFormatter)))

(defn ddc-query?
  [s]
  (and (str/includes? s "ddc_daemon") (str/includes? s "run_query")))

(def log-line-parts
  [:line :timestamp :corpus :node :query :opts])

(def log-line-pattern
  #"(?x)
    (\w{3}\s+\d{1,2}\s\d{2}:\d{2}:\d{2})\s # timestamp
    ddc\s ddc_daemon\[\d+\]:\s
    \(server:([^\#]+)\#(\d+)\)\s           # corpus and node
    >\sserver:[^\s]+\srecv\s
    \"run_query\sDistributed\#001
    (.+?)                                  # query
    \#001json\#001
    .+?                                    # opts
    \"")

(defn strip-extra-info
  [s]
  (let [[query _user-info] (str/split s #"\#012")
        query              (str/replace query #":'[^']+'$" "")]
    (str/trim query)))

(def timestamp-formatter
  (DateTimeFormatter/ofPattern "yyyy MMM ppd HH:mm:ss"))

(defn parse-timestamp
  [^String s]
  (->> (str (. (LocalDate/now) (getYear)) " " s)
       (.parse ^DateTimeFormatter timestamp-formatter)
       (LocalDateTime/from)
       (str)))

(defn parse-log-line
  [line]
  (when-let [log-line-match (re-find log-line-pattern line)]
    (-> (apply hash-map (interleave log-line-parts log-line-match))
        (select-keys [:corpus :query :timestamp])
        (update :query strip-extra-info)
        (update :timestamp parse-timestamp)
        (list))))


(comment
  (with-open [r (io/reader (io/file "data" "ddc.log"))]
    (->>
     (line-seq r)
     (sequence
      (comp (filter ddc-query?)
            (mapcat parse-log-line)
            (map :query)
            (distinct)))
     (sort)
     (str/join \newline)
     (spit (io/file "ddc-queries.txt")))))
