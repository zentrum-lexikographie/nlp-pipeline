(ns zdl.conllu
  "Parses and serializes annotated sentences in CoNLL-U format."
  (:require
   [clojure.string :as str]
   [zdl.schema :as schema]
   [charred.api :as charred]))

(def feature-key-patches
  {:space-after :space-after?})

(def feature-map-decoder-xf
  (comp
   (map #(str/split % #"=" 2))
   (map (fn [[k v]] [(schema/key-keyword k) (schema/tag-str v)]))))

(defn decode-feature-map
  [v]
  (when-let [s (and (string? v) (not-empty v))]
    (into {} feature-map-decoder-xf (str/split s #"\|"))))

(defn decode-space-after?
  [{:keys [space-after] :as token}]
  (-> token
      (dissoc :space-after)
      (assoc :space-after? (not= "NO" space-after))))

(defn decode-features
  [{:keys [feats deps misc] :as token}]
  (-> token
      (dissoc :feats :deps :misc)
      (merge (decode-feature-map feats)
             (decode-feature-map deps)
             (decode-feature-map misc))
      (decode-space-after?)))

(defn reset-n
  [i token]
  (-> token (dissoc :n) (assoc :n i)))

(defn reset-head
  [{:keys [head] :as token}]
  (let [head (some-> head parse-long)]
    (cond-> token head (assoc :head (when-not (zero? head) (dec head))))))

(def metadata-decode-xf
  (comp
   (map #(re-find #"^#\s*([^=]+)\s*=?\s*(.*)$" %))
   (remove nil?)
   (map (fn [[_ k v]] [(str/trim k) (some-> v str/trim not-empty)]))))

(defn decode-text
  [s]
  (try
    (if-let [t (some-> s charred/read-json)] (if (string? t) t s) s)
    (catch com.fasterxml.jackson.core.JsonParseException _ s)))

(defn decode-metadata
  [lines]
  (let [metadata (into {} metadata-decode-xf lines)
        id       (metadata "id")
        text     (metadata "text")]
    (cond-> (dissoc metadata "id" "text")
      id   (assoc :id id)
      text (assoc :text (decode-text text)))))


(defn unescape-underscore
  "ConLL-U uses `_` as `nil`."
  [s]
  (str/replace s #"__" "_"))

(defn decode-field
  "Translates empty/`nil` values."
  [v]
  (when-not (= "_" v) (-> v unescape-underscore not-empty)))

(def ^:dynamic *fields*
  "Field names and order for token records."
  [:n :form :lemma :upos :xpos :feats :head :deprel :deps :misc])

(defn decode-token
  "Tokens and their annotations are lines with field values separated by tabs or
  at least two consecutive spaces."
  [i s]
  (->> (str/split s #"\t| {2,}") (map decode-field) (zipmap *fields*)
       (decode-features) (reset-n i) (reset-head)
       (reduce (fn [m [k v]] (cond-> m (some? v) (assoc k v))) {})))

(defn comment-line?
  "Comment lines with sentence metadata start with a hash symbol."
  [s]
  (str/starts-with? s "#"))

(defn parse-chunk
  [s]
  (let [[m t] (split-with comment-line? s)
        m     (when (seq m) (decode-metadata m))
        s     (when (seq t) {:tokens (map-indexed decode-token t)})]
    (schema/decode-chunk
     (merge m
            {:sentences (cond-> [] s (conj s))}
            (when-not (:text m) {:text (str/join (schema/sentence->text s))})))))

(defn empty-line?
  [s]
  (= "" s))

(def lines->chunks-xf
  (comp
   (partition-by empty-line?)
   (remove (comp empty-line? first))))

(defn parse
  "Parses sentences read from a given reader and separated by empty lines."
  [lines]
  (sequence (comp lines->chunks-xf (map parse-chunk)) lines))
