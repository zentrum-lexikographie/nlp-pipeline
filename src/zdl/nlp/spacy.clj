(ns zdl.nlp.spacy
  (:refer-clojure :exclude [run!])
  (:require [babashka.process :refer [check process]]
            [babashka.fs :as fs]
            [zdl.nlp.tokenizer :as tokenizer]
            [zdl.schema :refer [tag-str]]
            [zdl.util :refer [assoc*]]
            [clojure.java.io :as io]
            [taoensso.timbre :as log]
            [clojure.data.csv :as csv]
            [clojure.string :as str]
            [zdl.env :as env]
            [zdl.schema :as schema]))

(def venv-dir
  env/python-venv-dir)

(def venv-python-exe
  (io/file venv-dir "bin" "python"))

(def venv-activate
  (io/file venv-dir "bin" "activate"))

(defn venv-cmd
  [cmd]
  ["/bin/bash" "-c" (str "source " (str venv-activate) " && " cmd)])

(defn run!
  [cmd]
  (check (process {:err :inherit :cmd cmd})))

(defn pip-install
  [reqs]
  (let [resource     (str "zdl/nlp/spacy/requirements." reqs ".txt")
        requirements (-> (io/resource resource) (slurp) (str/split #"\n"))
        requirements (str/join \space (map #(str "'" % "'") requirements))]
    (run! (venv-cmd (str "pip install " requirements)))))

(when-not (.exists venv-python-exe)
  (log/infof "Setting up Python virtual environment")
  (run! ["python" "-m" "venv" (str venv-dir)])
  (run! (venv-cmd "python -m pip install -U pip"))
  (pip-install "base")
  (when-not env/spacy-disable-model-download?
    (pip-install (if env/spacy-gpu? "gpu" "cpu"))))

(def tagger-script-source
  (io/resource "zdl/nlp/spacy/tagger.py"))

(def tagger-script
  (-> {:prefix "tagger-" :suffix ".py"}
      (fs/create-temp-file)
      (fs/delete-on-exit)))

(with-open [src  (io/input-stream tagger-script-source)
            dest (io/output-stream (fs/file tagger-script))]
  (io/copy src dest))

(def tagger-cmd
  (->>
   (cond-> ["python" (str tagger-script) "--batch" env/spacy-batch-size]
     env/spacy-gpu?   (conj "--gpu")
     env/spacy-gpu-id (conj "--gpuid" (str env/spacy-gpu-id)))
   (str/join \space)
   (venv-cmd)))

(defn extract-spans
  [n t]
  (for [[label i] (partition 2 (subvec t 24))] [label i n]))

(defn entity->span
  [[[label _i] ts]]
  {:type    :entity
   :label   (tag-str label)
   :targets (vec (sort (map (fn [[_label _i n]] n) ts)))})

(defn merge-entity-tagging
  [t]
  (->> (map-indexed extract-spans t)
       (mapcat identity)
       (group-by #(subvec % 0 2))
       (into [] (map entity->span))))

(defn pos-tag-str
  [s]
  (if (str/starts-with? s "$") "PUNCT" (tag-str s)))

(defn merge-token-tagging
  [token [_ _ _ deprel head lemma oov? upos xpos number gender case
          tense person mood degree punct-type verb-type verb-form
          conj-type part-type pron-type adp-type definite]]
  (-> token
      (assoc* :deprel     (some-> deprel not-empty tag-str))
      (assoc* :head       (some-> head not-empty parse-long))
      (assoc* :lemma      (some-> lemma not-empty))
      (assoc* :oov?       (some-> oov? (= "True")))
      (assoc* :upos       (some-> upos not-empty pos-tag-str))
      (assoc* :xpos       (some-> xpos not-empty pos-tag-str))
      (assoc* :number     (some-> number not-empty tag-str))
      (assoc* :gender     (some-> gender not-empty tag-str))
      (assoc* :case       (some-> case not-empty tag-str))
      (assoc* :tense      (some-> tense not-empty tag-str))
      (assoc* :person     (some-> person not-empty tag-str))
      (assoc* :mood       (some-> mood not-empty tag-str))
      (assoc* :degree     (some-> degree not-empty tag-str))
      (assoc* :punct-type (some-> punct-type not-empty tag-str))
      (assoc* :verb-type  (some-> verb-type not-empty tag-str))
      (assoc* :verb-form  (some-> verb-form not-empty tag-str))
      (assoc* :conj-type  (some-> conj-type not-empty tag-str))
      (assoc* :part-type  (some-> part-type not-empty tag-str))
      (assoc* :pron-type  (some-> pron-type not-empty tag-str))
      (assoc* :adp-type   (some-> adp-type not-empty tag-str))
      (assoc* :definite   (some-> definite not-empty tag-str))))

(defn merge-sentence-tagging
  [{:keys [tokens] :as s} t]
  (-> s
      (assoc :tokens (vec (mapv merge-token-tagging tokens t)))
      (assoc :spans (vec (merge-entity-tagging t)))))

(defn merge-chunk-tagging
  [{:keys [sentences] :as c} t]
  (assoc c :sentences (vec (->> (partition-by second t)
                                (mapv merge-sentence-tagging sentences)))))

(defn token->csv
  [{:keys [form space-after?]}]
  [form (if space-after? " " "")])

(defn sentence->csv
  [n {:keys [tokens]}]
  (into [(str n)] (mapcat token->csv tokens)))

(defn chunk->csv
  [n {:keys [sentences]}]
  (map (partial sentence->csv n) sentences))

(defn write-csv
  [out chunks]
  (csv/write-csv out (for [c (map-indexed chunk->csv chunks) s c] s)))

(defn tagged-seq
  [chunks]
  (let [proc (process {:err :string :cmd tagger-cmd})]
    (future
      (with-open [input (io/writer (:in proc))]
        (write-csv input chunks)))
    (future
      (let [{:keys [cmd exit err]} @proc]
        (when (pos? exit)
          (log/errorf "'%s': exit status %d\n\n%s" cmd exit err))))
    (with-open [output (io/reader (:out proc))]
      (->> (csv/read-csv output)
           (partition-by first)
           (mapv merge-chunk-tagging chunks)))))
