(ns zdl.nlp.hash
  (:import
   (com.dynatrace.hash4j.hashing Hasher64 Hashing)))

(def ^Hasher64 hashing
  (Hashing/polymurHash2_0 0 0))

(defn str->hash ^long
  [^CharSequence s]
  (. hashing (hashCharsToLong s)))
