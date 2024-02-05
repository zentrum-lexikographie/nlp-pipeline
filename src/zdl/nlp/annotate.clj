(ns zdl.nlp.annotate
  (:require
   [clojure.string :as str]
   [zdl.nlp.deps :as deps]
   [zdl.nlp.dwdsmor :as dwdsmor]
   [zdl.nlp.hash :as hash]
   [zdl.nlp.langid :as langid]))

(defn detect-lang
  [{:keys [text] :as segment}]
  (assoc segment :lang (langid/classify text)))

(defn lemmatize
  [{:keys [sentences] :as segment}]
  (if @dwdsmor/transducer
    (->>
     (for [{:keys [tokens] :as sentence} sentences]
       (->>
        (for [t tokens :let [a (dwdsmor/analyze (dwdsmor/fingerprint t) (t :form))]]
          (cond-> t a (assoc :lemma (:lemma a))))
        (vec) (assoc sentence :tokens)))
     (vec) (assoc segment :sentences))
    segment))

(defn collocations
  [{:keys [sentences] :as segment}]
  (->>
   (for [{:keys [deps] :as sentence} sentences]
     (let [sentence (cond-> sentence (nil? deps) (deps/assoc-deps))
           collocs  (deps/collocations sentence)]
       (cond-> sentence (seq collocs) (assoc :collocations (vec collocs)))))
   (vec) (assoc segment :sentences)))

(defn fingerprint
  [{:keys [sentences] :as segment}]
  (let [lemmata (->> (mapcat :tokens sentences)
                     (remove #(= "PUNCT" (:xpos %)))
                     (map (some-fn :lemma :form))
                     (map str/lower-case)
                     (into #{}))]
    (assoc segment :fingerprint (hash/->hex (hash/sim-hash lemmata)))))

(def annotate
  (comp collocations fingerprint lemmatize detect-lang))
