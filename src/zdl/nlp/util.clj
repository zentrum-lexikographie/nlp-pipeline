(ns zdl.nlp.util)

(defn assoc*
  [m k v]
  (cond-> m (some? v) (assoc k v)))
