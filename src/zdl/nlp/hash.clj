(ns zdl.nlp.hash
  (:import
   (com.dynatrace.hash4j.hashing Hasher64 Hashing)
   (com.dynatrace.hash4j.similarity ElementHashProvider SimilarityHashing SimilarityHashPolicy)
   (java.util HexFormat)
   (java.util.function ToLongFunction))
  (:require [clojure.string :as str]))

(def ^Hasher64 hashing
  (Hashing/komihash5_0))

(def ^ToLongFunction hash-fn
  (proxy [ToLongFunction] []
    (applyAsLong [^CharSequence s] (. hashing (hashCharsToLong s)))))

;; 8 bytes == 64 bits

(def sim-hash-components
  64)

(def ^SimilarityHashPolicy sim-hash-policy
  (SimilarityHashing/simHash sim-hash-components))

(defn sim-hash
  [ss]
  (let [hash-provider (ElementHashProvider/ofCollection ss hash-fn)]
    (.. sim-hash-policy (createHasher) (compute hash-provider))))

(def ^HexFormat hex-format
  (HexFormat/of))

(defn ->hex
  [^bytes bs]
  (.. hex-format (formatHex bs)))

(defn distance
  [^bytes h1 ^bytes h2]
  (- sim-hash-components (. sim-hash-policy (getNumberOfEqualComponents h1 h2))))

(defn fingerprint
  [{:keys [sentences] :as chunk}]
  (let [lemmata (->> (mapcat :tokens sentences)
                     (remove #(= "PUNCT" (:xpos %)))
                     (map (some-fn :lemma :form))
                     (map str/lower-case)
                     (into #{}))]
    (cond-> chunk (seq lemmata) (assoc :fingerprint (->hex (sim-hash lemmata))))))

(defn deduplicate
  ([chunks]
   (deduplicate chunks #{}))
  ([chunks seen]
   (when-let [{:keys [fingerprint] :as chunk} (first chunks)]
     (lazy-seq
      (if (seen fingerprint)
        (deduplicate (rest chunks) seen)
        (cons chunk (deduplicate (rest chunks) (conj seen fingerprint))))))))
