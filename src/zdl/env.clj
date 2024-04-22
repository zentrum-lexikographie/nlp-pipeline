(ns zdl.env
  (:import
   (io.github.cdimascio.dotenv Dotenv))
  (:require [clojure.string :as str]
            [clojure.java.io :as io]))

(def ^Dotenv dot-env
  (.. Dotenv (configure) (ignoreIfMissing) (load)))

(defn get-env
  ([k]
   (get-env k nil))
  ([^String k df]
   (let [k (str "ZDL_NLP_" k)]
     (or (System/getenv k) (.get dot-env k) df))))

(def environment
  (get-env "ENVIRONMENT" "dev"))

(def dev?
  (= "dev" environment))

(def python-venv-dir
  (io/file (get-env "PYTHON_VENV_DIR" "venv")))

(def spacy-disable-model-download?
  (some? (not-empty (get-env "SPACY_DISABLE_MODEL_DOWNLOAD"))))

(def spacy-gpu?
  (some? (not-empty (get-env "SPACY_GPU"))))

(def spacy-model-suffix
  (if spacy-gpu? "dist" "lg"))

(def spacy-dep-model
  (str "de_dwds_dep_hdt_" spacy-model-suffix))

(def spacy-ner-model
  (str "de_dwds_ner_" spacy-model-suffix))

(def spacy-batch-size
  (parse-long (get-env "SPACY_BATCH_SIZE" "1")))

(def spacy-parallel
  (parse-long (get-env "SPACY_PARALLEL" "1")))

(def dwdsmor-lemmatizer-automaton
  (some-> (get-env "DWDSMOR_LEMMATIZER_AUTOMATON")
          (io/file)))

(def dstar-corpora-user
  (get-env "DSTAR_CORPORA_USER"))

(def dstar-corpora-password
  (get-env "DSTAR_CORPORA_PASSWORD"))

(def dstar-corpora-credentials
  (when (and dstar-corpora-user dstar-corpora-password)
    {:basic-auth {:user dstar-corpora-user
                  :pass dstar-corpora-password}}))

(def discolm-url
  (get-env "DISCOLM_URL" "http://localhost:8000/v1/"))

(def discolm-model
  (get-env "DISCOLM_MODEL" "DiscoResearch/DiscoLM_German_7b_v1"))

(def discolm-auth-token
  (get-env "DISCOLM_AUTH_TOKEN"))

(def openai-url
  (get-env "OPENAI_URL" "https://api.openai.com/v1/"))

(def openai-model
  (get-env "OPENAI_MODEL" "gpt-3.5-turbo"))

(def openai-auth-token
  (get-env "OPENAI_AUTH_TOKEN"))
