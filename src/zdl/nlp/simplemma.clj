(ns zdl.nlp.simplemma
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   (java.io PushbackReader)
   (java.util.zip GZIPInputStream)))

(defonce lexicon
  (with-open [r (-> (io/resource "zdl/nlp/simplemma/lexicon.edn.gz")
                    (io/input-stream)
                    (GZIPInputStream.)
                    (io/reader)
                    (PushbackReader.))]
    (edn/read r)))

(def punct-or-symbol
  (partial re-matches #"[\p{P}\p{S}]"))

(def contraction
  (comp
   #{"am" "ans" "aufm" "aufn" "aufs" "ausm"
     "beim"
     "durchs"
     "fürn" "fürs"
     "hinterm" "hintern" "hinters"
     "im" "ins"
     "sowas"
     "ums"
     "unterm" "untern" "unters"
     "vom" "vorm" "vorn" "vors"
     "wenns"
     "zum" "zur"
     "überm" "übern" "übers"}
   str/lower-case))

(declare lookup)

(defn lookup*
  [s]
  (or (lookup "de" s)
      (lookup "en" s)
      (lookup "fr" s)))

(defn hyphen-decomposition
  [s]
  (let [components (str/split s #"-")]
    (when (second components)
      (let [capitalized? (Character/isUpperCase (first s))
            merged (cond-> (str/lower-case (str/join components))
                     capitalized? (str/capitalize))]
        (or (lookup* merged)
            (when-let [candidate (lookup* (last components))]
              (str/join "-" (concat (butlast components) (list candidate)))))))))

(defn lookup
  ([s]
   (when s (or (punct-or-symbol s)
               (contraction s)
               (lookup* s)
               (hyphen-decomposition s))))
  ([lang s]
   (or (get-in lexicon [lang s])
       (get-in lexicon [lang (str/lower-case s)])
       (get-in lexicon [lang (str/capitalize s)]))))

(defn lemmatize
  [{:keys [form] :as token}]
  (let [lemma (lookup form)] (cond-> token lemma (assoc :lemma lemma))))
