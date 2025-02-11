(ns zdl.nlp.transnormer
  (:require [clojure.string :as str]
            [zdl.python :as python]
            [clojure.java.io :as io]
            [zdl.env :as env]
            [taoensso.timbre :as log]
            [libpython-clj2.python :as py]))

(def transformers-exe
  (io/file python/venv-dir "bin" "transformers-cli"))

(defn tagger-pip-install!
  [pkgs]
  (let [resource     (str "zdl/nlp/transnormer/requirements." pkgs ".txt")
        requirements (-> (io/resource resource) (slurp) (str/split #"\n"))]
    (apply python/install! requirements)))

(when-not (python/installed? "transformers")
  (let [environment (if env/spacy-gpu? "gpu" "cpu")]
    (log/infof "Initializing transnormer [%s]" environment)
    (tagger-pip-install! environment)))

(require '[libpython-clj2.require :refer [require-python]])

(py/from-import transformers pipeline)

(comment
  (pipeline "text2text-generation"
            :model "ybracke/transnormer-dtaeval-v01"))
