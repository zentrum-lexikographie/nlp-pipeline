(ns zdl.metadata
  (:require
   [clojure.string :as str])
  (:import
   (java.time Instant LocalDate ZoneOffset)))

(defn parse-v
  [s]
  (some-> s not-empty))

(defn parse-vs
  [s]
  (some->> (some-> (parse-v s) (str/split #":"))
           (map not-empty) (remove nil?) (seq)))

(defn parse-date
  [s]
  (when-let [^String s (parse-v s)]
    (try
      (condp = (count s)
        10 (str (LocalDate/parse s))
        4  (str (LocalDate/parse (str s "-01-01")))
        nil)
      (catch Throwable _))))

(defn parse-dates
  [s]
  (some->> (parse-vs s) (map parse-date) (remove nil?) (seq)))

(defn parse-one-date
  [s]
  (some->> s (parse-dates) (first)))

(defn parse-timestamp
  [s]
  (when-let [^String s (parse-v s)]
    (try (str (Instant/parse s)) (catch Throwable _))))

(defn parse-timestamp-date
  [s]
  (when-let [^String s (parse-v s)]
    (try
      (str (.. (Instant/parse s) (atOffset ZoneOffset/UTC) (toLocalDate)))
      (catch Throwable _))))

(defn parse-page
  [v]
  (when v (str v)))

(defn parse-bibl
  [page s]
  (some-> s (cond-> page (str/replace #"#page#" page))))
