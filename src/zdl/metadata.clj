(ns zdl.metadata
  (:require
   [clojure.string :as str])
  (:import
   (java.time Instant LocalDate)))

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
        10 (LocalDate/parse s)
        4  (LocalDate/parse (str s "-01-01"))
        nil)
      (catch Throwable _))))

(defn parse-dates
  [s]
  (some->> (parse-vs s) (map parse-date) (remove nil?) (seq)))

(defn parse-timestamp
  [s]
  (when-let [^String s (parse-v s)]
    (try (Instant/parse s) (catch Throwable _))))

(defn parse-page
  [v]
  (when v (str v)))
