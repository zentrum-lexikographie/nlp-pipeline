(ns zdl.nlp.gdex
  (:require
   [clojure.data.csv :as csv]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(defn finite-verb?
  [{:keys [upos xpos verb-form] :as token}]
  (-> (or (and (or (= "VERB" upos) (= "AUX" upos)) (= "FIN" verb-form))
          (str/ends-with? xpos "FIN"))
      (when token)))

(defn subject?
  [{:keys [upos] :as token}]
  (-> (or (= "NOUN" upos) (= "PROPN" upos) (= "PRON" upos))
      (when token)))

(defn root-or-child?
  [root {:keys [n head] :as token}]
  (-> (or (= root n) (= root head))
      (when token)))

(defn subject-and-finite-verb?
  [{:keys [tokens] :as sentence}]
  (let [root (->> tokens (filter (comp nil? :head)) first :n)]
    (-> (and (some->> (some subject? tokens) (root-or-child? root))
             (some->> (some finite-verb? tokens) (root-or-child? root)))
        (when sentence))))

(defn parsed?
  [{:keys [tokens] :as sentence}]
  (when (second tokens)
    (let [ft (first tokens)
          lt (last tokens)]
      (when (and (some-> ft :xpos (not= "PUNCT"))
                 (some-> lt :xpos (= "PUNCT"))
                 (some-> ft :form first (Character/isUpperCase)))
        sentence))))

(def illegal-chars
  (into #{} "<>|[]/\\^@"))

(defn illegal-chars?
  [sentence]
  (when (some illegal-chars (:text sentence))
    sentence))

(def profanity-blacklist
  "Based on VulGer, a lexicon covering words from the lower end of the
  German language register — terms typically considered rough, vulgar,
  or obscene."
  (with-open [r (io/reader (io/resource "zdl/nlp/gdex/vulger.csv"))]
    (->> (csv/read-csv r)
         (drop 1)
         (filter (fn [[_lemma score]] (< (parse-double score) 0)))
         (map first)
         (into (sorted-set)))))

(defn blacklisted-lemmata?
  [{:keys [tokens] :as sentence}]
  (when (some profanity-blacklist (map :lemma tokens))
    sentence))

(def rare-chars
  (into #{} "0123456789\"'.,!?)(;:-"))

(def rare-chars-penalty
  0.125)

(defn rare-chars-factor
  [{:keys [text] :as _sentence}]
  (max 0 (- 1 (* (count (filter rare-chars text)) rare-chars-penalty))))

(def named-entity-penalty
  0.1667)

(defn named-entity-factor
  [{:keys [spans] :as _sentence}]
  (max 0 (- 1 (* (count (filter (comp #(= :entity %) :type) spans))
                 named-entity-penalty))))

(def min-interval
  10)

(def max-interval
  20)

(defn optimal-interval-factor
  [{:keys [tokens] :as _sentence}]
  (let [n (count tokens)]
    (cond
      (<= min-interval n max-interval) 1
      (< n min-interval)               (- 1 (/ (- min-interval n) min-interval))
      :else                            (- 1 (/ (- n max-interval) n)))))

(def deixis-lemmata
  #{"jetzt" "heute" "gestern" "morgen" "dann" "damals" "bald" "kürzlich"
    "hier" "dort" "über" "da" "vor" "hinter" "links" "von" "rechts"
    "oben" "unten"})

(def deixis-pronoun-types
  #{"PDS" "PIS" "PPER" "PPOSS"})

(defn deixis?
  [{:keys [lemma xpos]}]
  (or (deixis-pronoun-types xpos) (deixis-lemmata lemma)))

(def deixis-penalty
  0.034)

(defn deixis-factor
  [{:keys [tokens] :as _sentence}]
  (max 0 (- 1 (* (count (filter deixis? tokens)) deixis-penalty))))

(def common-lemmata
  (->> (for [collection ["kernbasis" "webxl" "zeitungenxl"]]
         (-> (format "zdl/nlp/gdex/%s.top.edn" collection)
             (io/resource) (slurp) (read-string)))
       (flatten)
       (into (sorted-set))))

(def rare-lemmata-penalty
  0.034)

(defn rare-lemmata-factor
  [{:keys [tokens] :as _sentence}]
  (max 0 (- 1 (* (count (remove (comp common-lemmata :lemma) tokens))
                 rare-lemmata-penalty))))

(defn knockout-factor
  [sentence]
  (if (or (not (subject-and-finite-verb? sentence))
          (not (parsed? sentence))
          (illegal-chars? sentence)
          (blacklisted-lemmata? sentence))
    0
    1))

(defn gradual-factor
  [sentence]
  (-> 1
      (* (rare-chars-factor sentence))
      (* (named-entity-factor sentence))
      (* (optimal-interval-factor sentence))
      (* (deixis-factor sentence))
      (* (rare-lemmata-factor sentence))))

(defn score
  [sentence]
  (double (+ (* 0.5 (knockout-factor sentence))
             (* 0.5 (gradual-factor sentence)))))

(defn good?
  [score]
  (<= 0.5 score))
