(ns zdl.ddc.tabs
  (:require
   [clojure.string :as str]
   [zdl.schema :as schema]))

(defn decode-field
  "Translates empty/`nil` values."
  [v]
  (not-empty v))

(defn decode-space-before?
  [{:keys [space-before?] :as token}]
  (cond-> token
    space-before? (assoc :space-before? (= "1" space-before?))))

(def ^:dynamic *fields*
  "Field names and order for token records."
  [:form :xpos :lemma :space-before?])

(defn decode-token
  "Tokens and their annotations are lines with field values separated by tabs or
  at least two consecutive spaces."
  [i s]
  (as-> s $
    (str/split $ #"\t")
    (map decode-field $)
    (zipmap *fields* $)
    (decode-space-before? $)
    (assoc $ :n i)))

(def metadata-key-parser-xf
  (comp
   (map #(-> % (str/replace #"^_+" "") (str/replace #"_+$" "")))
   (mapcat (fn [c]
          (if-let [index (re-matches #"^([^\[]+)(?:\[(\d+)\])?$" c)]
            (let [[_ i1 i2] index]
              (cond-> [i1] i2 (conj i2)))
            [c])))))

(defn parse-metadata-key
  [k]
  (into [] metadata-key-parser-xf (rest (str/split (str/trim k) #"[:\\.]"))))

(def metadata-decode-xf
  (comp
   (map #(re-find #"^%%\$\s*([^=]+)\s*=?\s*(.*)$" %))
   (remove nil?)))

(defn decode-metadata
  [lines]
  (reduce
   (fn [m [_ k v]]
     (if-let [v (some-> v str/trim not-empty)]
       (assoc-in m (parse-metadata-key k) v)
       m))
   {}
   (sequence metadata-decode-xf lines)))

(defn comment-line?
  "Comment lines with sentence metadata start with a hash symbol."
  [s]
  (str/starts-with? s "%%"))

(defn empty-line?
  [s]
  (= "" s))

(defn parse-chunk
  [s]
  (let [[metadata tokens] (split-with comment-line? s)]
    (cond-> {}
      (seq tokens)   (assoc :tokens (into [] (map-indexed decode-token) tokens))
      (seq metadata) (merge (decode-metadata metadata)))))

(def parse-xf
  (comp
   (partition-by empty-line?)
   (remove (comp empty-line? first))
   (map parse-chunk)
   (map schema/decode-sentence)))

(defn parse
  "Parses sentences read from a given reader and separated by empty lines."
  [lines]
  (schema/assoc-space-after (sequence parse-xf lines)))

(defn segment-by
  [start? sentences]
  (when-let [start (first sentences)]
    (when-not (start? start)
      (throw (new AssertionError))))
  (sequence
   (comp
    (partition-by start?)
    (partition-all 2)
    (map flatten))
   sentences))

(defn doc-start?
  [{:keys [metadata]}]
  (get metadata :meta))

(def docs
  (partial segment-by doc-start?))

(defn p-start?
  [{:keys [metadata] :as sentence}]
  (or (doc-start? sentence) (get-in metadata [:break :p])))

(def paras
  (partial segment-by p-start?))
