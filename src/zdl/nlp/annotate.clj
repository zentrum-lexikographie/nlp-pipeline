(ns zdl.nlp.annotate
  (:require
   [clojure.string :as str]
   [zdl.nlp.deps :as deps]
   [zdl.nlp.dwdsmor :as dwdsmor]
   [zdl.nlp.gdex :as gdex]
   [zdl.nlp.hash :as hash]
   [zdl.nlp.langid :as langid]
   [zdl.nlp.spacy :as spacy]))

(defn detect-lang
  [{:keys [text] :as chunk}]
  (assoc chunk :lang (langid/classify text)))

(defn lemmatize
  [{:keys [sentences] :as chunk}]
  (if @dwdsmor/transducer
    (->>
     (for [{:keys [tokens] :as sentence} sentences]
       (->>
        (for [t tokens :let [a (dwdsmor/analyze (dwdsmor/fingerprint t) (t :form))]]
          (cond-> t a (assoc :lemma (:lemma a))))
        (vec) (assoc sentence :tokens)))
     (vec) (assoc chunk :sentences))
    chunk))

(defn gdex
  [{:keys [sentences] :as chunk}]
  (->>
   (for [sentence sentences] (assoc sentence :gdex (gdex/score sentence)))
   (vec) (assoc chunk :sentences)))

(defn collocations
  [{:keys [sentences] :as chunk}]
  (->>
   (for [{:keys [deps] :as sentence} sentences]
     (let [sentence (cond-> sentence (nil? deps) (deps/assoc-deps))
           collocs  (deps/collocations sentence)]
       (cond-> sentence (seq collocs) (update :spans (fnil into []) collocs))))
   (vec) (assoc chunk :sentences)))

(defn fingerprint
  [{:keys [sentences] :as chunk}]
  (let [lemmata (->> (mapcat :tokens sentences)
                     (remove #(= "PUNCT" (:xpos %)))
                     (map (some-fn :lemma :form))
                     (map str/lower-case)
                     (into #{}))]
    (assoc chunk :fingerprint (hash/->hex (hash/sim-hash lemmata)))))

(def annotate-chunk
  (comp collocations fingerprint gdex lemmatize detect-lang))

(def annotate
  (comp (partial pmap annotate-chunk) spacy/tagged-seq))

(defn deduplicate
  ([chunks]
   (deduplicate chunks #{}))
  ([chunks seen]
   (when-let [{:keys [fingerprint] :as chunk} (first chunks)]
     (lazy-seq
      (if (seen fingerprint)
        (deduplicate (rest chunks) seen)
        (cons chunk (deduplicate (rest chunks) (conj seen fingerprint))))))))
