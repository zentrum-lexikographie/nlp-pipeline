(ns zdl.nlp.top-lemma
  (:gen-class)
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [next.jdbc :as jdbc])
  (:import
   (java.text Collator Normalizer Normalizer$Form)
   (java.util Locale)))

(def pos-included?
  (complement
   (some-fn #(str/starts-with? % "$")
            #(str/starts-with? % "CARD")
            #(str/starts-with? % "FM")
            #(str/starts-with? % "NE")
            #(str/starts-with? % "XY"))))

(defn valid-lemma?
  [s]
  (as-> s $
    (Normalizer/normalize $ Normalizer$Form/NFD)
    (str/replace $ #"\p{InCombiningDiacriticalMarks}" "")
    (str/replace $ "ß" "ss")
    (re-matches #"^[\p{Alpha}\p{Digit}\-]+$" $)))

(def ^Collator de-collator
  (doto (Collator/getInstance Locale/GERMAN) (.setStrength Collator/PRIMARY)))

(defn de-sort-key
  [s]
  (.getCollationKey de-collator s))

(defn sqlite-connection
  [f]
  (jdbc/get-connection {:dbtype "sqlite" :dbname (. (io/file f) (getPath))}))

(def top-count
  17000)

(defn -main
  [& lexdb-sqlite-files]
  (let [freqs (volatile! {})
        add   (fn [_ {:lex/keys [l p f]}]
                (when (and (pos-included? p) (valid-lemma? l))
                  (vswap! freqs update l (fnil + 0) f)))]
    (doseq [f lexdb-sqlite-files]
      (jdbc/with-transaction [tx (sqlite-connection f)]
        (reduce add nil (jdbc/plan tx ["select * from lex"]))))
    (let [freqs   @freqs
          lemmata (sort-by (comp - freqs) (keys freqs))]
      (pr (into [] (take top-count lemmata)))
      (flush))))
