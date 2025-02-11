(ns zdl.nlp.flair
  (:require
   [taoensso.timbre :as log]
   [zdl.python :as python]))

(when-not (python/installed? "flair")
  (log/info "Initializing flair")
  (python/install! "scipy==1.12" "flair"))

(require '[libpython-clj2.python :as py]
         '[libpython-clj2.require :refer [require-python]])

(require-python '[flair :as flair]
                '[flair.embeddings :refer [FlairEmbeddings]])

#_(py/from-import transformers pipeline)

(def model
  (py/py.- (FlairEmbeddings "multi-forward-fast") lm))

(comment
  (py/py. model calculate_perplexity "Das ist ein Test."))
