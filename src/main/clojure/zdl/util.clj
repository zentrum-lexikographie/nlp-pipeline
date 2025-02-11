(ns zdl.util
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   (java.text Normalizer Normalizer$Form)))

(defn assoc*
  [m k v]
  (cond-> m (some? v) (assoc k v)))

(defn pr-edn
  [& args]
  (binding [*print-length*   nil
            *print-dup*      nil
            *print-level*    nil
            *print-readably* true]
    (apply pr args)))

(defn pr-edn-str
  [& args]
  (with-out-str
    (apply pr-edn args)))

(defn spit-edn
  [f & args]
  (with-open [w (io/writer (io/file f))]
    (binding [*out* w]
      (apply pr-edn args))))

(defn slurp-edn
  [f]
  (read-string (slurp (io/file f))))

(defn form->id
  [form]
  (-> form
      (Normalizer/normalize Normalizer$Form/NFD)
      (str/replace #"\p{InCombiningDiacriticalMarks}" "")
      (str/replace "ß" "ss")
      (str/replace " " "_")
      (str/replace #"[^\p{Alpha}\p{Digit}\-_]" "_")
      (str/replace #"_+" "_")))
