(ns zdl.nlp.hash
  (:import
   (com.dynatrace.hash4j.hashing Hasher64 Hashing)
   (com.dynatrace.hash4j.similarity ElementHashProvider SimilarityHashing SimilarityHashPolicy)
   (java.util HexFormat)
   (java.util.function ToLongFunction)))

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
