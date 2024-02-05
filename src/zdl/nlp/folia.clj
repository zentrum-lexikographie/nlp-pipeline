(ns zdl.nlp.folia
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [zdl.nlp.annotation :as anno]))

(defn tag->str
  [k]
  (some-> k name str/upper-case))

(defn class-prop
  [k v]
  (when v [k {:class v}]))

(defn feat-prop
  [k v]
  (when v [:feat {:subset (name k) :class v}]))

(defn word-id
  [sentence-id n]
  (str sentence-id ".w." (inc n)))

(defn sentence
  ([s]
   (sentence "s" s))
  ([id {:keys [tokens language entities collocations]}]
   (let [word-id (partial word-id id)]
     [:s {:xml:id id}
      (class-prop :lang language)
      (for [token tokens]
        [:w (cond-> {:xml:id (word-id (token :n))}
              (not (token :space-after?)) (assoc :space "no"))
         [:t (token :form)]
         (class-prop :lemma (token :lemma))
         (into (class-prop :pos (some-> token :xpos tag->str))
               (list (feat-prop :num    (some-> token :number tag->str))
                     (feat-prop :gender (some-> token :gender tag->str))
                     (feat-prop :case   (some-> token :case tag->str))
                     (feat-prop :person (some-> token :person str))
                     (feat-prop :tense  (some-> token :tense tag->str))))])
      (when (some :deprel tokens)
        [:dependencies
         (for [{:keys [n head deprel]} tokens :when head]
           [:dependency {:class (tag->str deprel)}
            [:hd  [:wref {:id (word-id head)}]]
            [:dep [:wref {:id (word-id n)}]]])])
      (when entities
        [:entities
         (for [{:keys [label start end]} entities]
           [:entity {:class (tag->str label)}
            (for [n (range start end)]
              [:wref {:id (word-id n)}])])])
      (when collocations
        [:collocations
         (for [{:keys [k path]} collocations]
           [:collocation {:class (tag->str k)}
            (for [n path]
              [:wref {:id (word-id n)}])])])])))

(comment
  (require '[zdl.nlp.tokenizer :refer [tokenize]]
           '[zdl.nlp.tagger :refer [with-tagger combined trankit flair simplemma dwdsmor]]
            '[zdl.nlp.annotation :as anno])

  (with-tagger
    [tagged (combined [(trankit) (flair) (simplemma) (dwdsmor)])]
    (tokenize (str "FAZ: Wenn der Erfinder des berühmten Turing-Tests, Alan Turing, "
                   "diese Modelle sehen würde, würde er aber doch sagen: "
                   "Das ist eine Intelligenz. Die KI besteht ja den Test. "
                   "Alan Turing: I would have failed that test!"))
    (spit (io/file "sample-sentence.tagged.edn")
          (pr-str (into [] (map anno/process) tagged))))


  (->> (clojure.edn/read-string (slurp (io/file "sample-sentence.tagged.edn")))
       (map (comp #_xml/xml->str #_gxml/sexp->node sentence))
       (vec)))
