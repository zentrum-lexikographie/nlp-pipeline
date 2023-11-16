(ns zdl.nlp.tagger
  (:require
   [camel-snake-kebab.core :as csk]
   [clojure.data.csv :as csv]
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   (java.io Closeable Writer)
   (java.lang ProcessBuilder$Redirect)))

(defprotocol Tagger
  (write-csv-chunk [this v])
  (close-input [this])
  (sentence-seq [this])
  (exit-value [this])
  (destroy [this]))

(defn close!
  [^Closeable c]
  (try (.close c) (catch Throwable _)))

(defn destroy!
  [^Process proc]
  (try (when (.isAlive proc) (.destroy proc)) (catch Throwable _)))

(def sentence-separator-record?
  (partial = [""]))

(defn process
  [cmd xform]
  (let [cmd     ^"[Ljava.lang.String;" (into-array String cmd)
        process (.. (ProcessBuilder. cmd)
                    (redirectError ProcessBuilder$Redirect/INHERIT)
                    (start))
        input   (io/writer (.getOutputStream process) :encoding "UTF-8")
        output  (io/reader (.getInputStream process) :encoding "UTF-8")]
    (reify Tagger
      (write-csv-chunk [_ v]
        (.write ^Writer input ^String v)
        (.flush ^Writer input))
      (close-input [_]
        (close! input))
      (sentence-seq [_]
        (sequence (comp
                   (partition-by sentence-separator-record?)
                   (remove (comp sentence-separator-record? first))
                   xform)
                  (csv/read-csv output)))
      (exit-value [_]
        (.waitFor ^Process process)
        (.exitValue ^Process process))
      (destroy [_]
        (close! input)
        (close! output)
        (destroy! process)))))

(defn interleave*
  [colls]
  (lazy-seq
   (when-let [ss (seq (filter seq colls))]
     (concat (map first ss) (interleave* (map rest ss))))))

(defn parallel
  [taggers]
  (let [taggers (vec taggers)
        n       (count taggers)
        i       (volatile! -1)]
    (reify Tagger
      (write-csv-chunk [_ v]
        (write-csv-chunk (taggers (mod (vswap! i inc) n)) v))
      (close-input [_]
        (doseq [tagger taggers] (close-input tagger)))
      (sentence-seq [_]
        (interleave* (map sentence-seq taggers)))
      (exit-value [_]
        (reduce max (map exit-value taggers)))
      (destroy [_]
        (doseq [tagger taggers] (destroy tagger))))))

(defn merge-sentences*
  [{t1 :tokens :as s1} {t2 :tokens :as s2}]
  (assoc (merge s1 s2) :tokens (mapv merge t1 t2)))

(defn merge-sentences
  [& tagged-results]
  (reduce merge-sentences* tagged-results))

(defn combined
  ([taggers]
   (combined merge-sentences taggers))
  ([combine-fn taggers]
   (reify Tagger
     (write-csv-chunk [_ v]
       (doseq [tagger taggers] (write-csv-chunk tagger v)))
     (close-input [_]
       (doseq [tagger taggers] (close-input tagger)))
     (sentence-seq [_]
       (apply map combine-fn (map sentence-seq taggers)))
     (exit-value [_]
       (reduce max (map exit-value taggers)))
     (destroy [_]
       (doseq [tagger taggers] (destroy tagger))))))

(defn split-by-comma
  [s]
  (str/split s #","))

(defn normalize-punct
  [s]
  (if (str/starts-with? s "$") "PUNCT" s))

(def tag->kw
  (memoize #(some-> % normalize-punct csk/->kebab-case-keyword)))

(defn tags->kw-set
  [s]
  (some->> s split-by-comma (into (sorted-set) (map tag->kw))))

(defn csv-empty->nil
  [record]
  (map not-empty record))


(defn parse-csv
  [token-csv->map records]
  {:tokens (into [] (map (comp token-csv->map csv-empty->nil) records))})

(defn parse-csv-xf
  [token-csv->map]
  (map (partial parse-csv token-csv->map)))

(defn spacy-csv->token
  [[lemma upos xpos deprel head
    case gender number person tense mood degree verb-form pron-type]]
  (cond-> nil
    lemma     (assoc :lemma (str/trim lemma))
    upos      (assoc :upos (tag->kw upos))
    xpos      (assoc :xpos (tag->kw xpos))
    deprel    (assoc :deprel (tag->kw deprel))
    head      (assoc :head (parse-long head))
    case      (assoc :case (tag->kw case))
    gender    (assoc :gender (tag->kw gender))
    number    (assoc :number (tag->kw number))
    person    (assoc :person (parse-long person))
    tense     (assoc :tense (tag->kw tense))
    mood      (assoc :mood (tags->kw-set mood))
    degree    (assoc :degree (tag->kw degree))
    verb-form (assoc :verb-from (tag->kw verb-form))
    pron-type (assoc :pron-type (tag->kw pron-type))))

(defn spacy
  []
  (process
   ["python" "-m" "zdl.spacy"]
   (parse-csv-xf spacy-csv->token)))

(defn trankit-csv->token
  [[upos xpos deprel head
    case gender number person tense mood degree verb-form pron-type]]
  (cond-> nil
    upos      (assoc :upos (tag->kw upos))
    xpos      (assoc :xpos (tag->kw xpos))
    deprel    (assoc :deprel (tag->kw deprel))
    head      (assoc :head (dec (parse-long head)))
    case      (assoc :case (tag->kw case))
    gender    (assoc :gender (tag->kw gender))
    number    (assoc :number (tag->kw number))
    person    (assoc :person (parse-long person))
    tense     (assoc :tense (tag->kw tense))
    mood      (assoc :mood (tags->kw-set mood))
    degree    (assoc :degree (tag->kw degree))
    verb-form (assoc :verb-from (tag->kw verb-form))
    pron-type (assoc :pron-type (tag->kw pron-type))))

(defn trankit
  []
  (process
   ["python" "-m" "zdl.trankit"]
   (parse-csv-xf trankit-csv->token)))

(defn dwdsmor-analysis->map
  [[analysis lemma pos gender case number tense person]]
  (cond-> nil
    analysis (assoc :analysis analysis)
    lemma    (assoc :lemma lemma)
    pos      (assoc :pos (tag->kw pos))
    gender   (assoc :gender (tag->kw gender))
    case     (assoc :case (tag->kw case))
    number   (assoc :number (tag->kw number))
    tense    (assoc :tense (tag->kw tense))
    person   (assoc :person (parse-long person))))

(defn dwdsmor-csv->maps
  [record]
  (let [analyses (partition-all 8 record)
        analyses (into [] (comp (map dwdsmor-analysis->map) (filter :lemma)) analyses)]
    (cond-> nil (seq analyses) (assoc :dwdsmor analyses))))

(defn dwdsmor
  []
  (process
   ["python" "-m" "zdl.dwdsmor" "-m" "resources/dwdsmor/dwdsmor.ca"]
   (parse-csv-xf dwdsmor-csv->maps)))

(defn simplemma-csv->maps
  [[lemma _]]
  (cond-> nil lemma (assoc :lemma lemma)))

(defn simplemma
  []
  (process
   ["python" "-m" "zdl.simplemma"]
   (parse-csv-xf simplemma-csv->maps)))

(defn flair-entity-csv->map
  [[label n score]]
  {:label (tag->kw label)
   :n     (parse-long n)
   :score (parse-double score)})

(defn flair-csv->token
  [record]
  (when-let [fields (seq (rest record))]
    {:entities (into [] (map flair-entity-csv->map) (partition 3 fields))}))

(defn flair-group-entities
  [{:keys [tokens] :as sentence}]
  (if (some :entities tokens)
    (let [entities (for [[i token] (map-indexed list tokens)
                         entity    (:entities token)]
                     (assoc entity :token i))
          entities (vals (group-by :n entities))]
      (->> (for [[entity :as entity-group] entities]
             (let [tokens (into [] (sort (map :token entity-group)))]
               (-> entity
                   (dissoc :n :token)
                   (assoc :start (first tokens) :end (inc (last tokens))))))
           (vec)
           (assoc sentence :entities)))
    sentence))

(defn flair
  []
  (process
   ["python" "-m" "zdl.flair"]
   (comp (parse-csv-xf flair-csv->token) (map flair-group-entities))))

(defn sentence->csv-chunk
  [{:keys [text tokens]}]
  (->>
   (concat
    ;; record with single field, containing sentence text
    (list [text])
    ;; records, representing tokens (token text and space flag)
    (for [t tokens] [(:text t) (if (:space-after? t) "1" "0")])
    ;; empty record, marking sentence end
    (list []))
    ;; to string
    (csv/write-csv *out*)
    (with-out-str)))

(defn sentences->tagger
  [sentences tagger]
  (try
    (doseq [chunk (map sentence->csv-chunk sentences)]
      (write-csv-chunk tagger chunk))
    (close-input tagger)
    (catch Throwable t t)))

(defmacro with-tagger
  [[results tagger] sentences & body]
  `(let [tagger#    ~tagger
         sentences# ~sentences
         exception# (future (sentences->tagger sentences# tagger#))
         ~results   (map merge-sentences sentences# (sentence-seq tagger#))]
     (try
       ~@body
       (finally
         (destroy tagger#)
         (some-> exception# deref throw)
         (when (pos? (exit-value tagger#))
           (throw (ex-info "Tagger exit value > 0" {})))))))

(defn tag!
  [tagger sentences]
  (reify clojure.lang.IReduceInit
    (reduce [_this f init]
      (let [exception (future (sentences->tagger sentences tagger))
            results   (map merge-sentences sentences (sentence-seq tagger))]
        (try
          (loop [acc init results results]
            (let [result (first results)]
              (if (or (reduced? acc) (nil? result))
                (unreduced acc)
                (recur (f acc result) (rest results)))))
          (finally
            (destroy tagger)
            (some-> exception deref throw)
            (when (pos? (exit-value tagger))
              (throw (ex-info "Tagger exit value > 0" {})))))))))

(defn tag->vec!
  [tagger sentences]
  (reduce conj [] (tag! tagger sentences)))
