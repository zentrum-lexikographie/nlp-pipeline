(ns zdl.nlp.sbert)

(require '[libpython-clj2.python :as py]
         '[libpython-clj2.require :refer [require-python]])

;; https://sease.io/2023/01/apache-solr-neural-search-tutorial.html

(require-python '[sentence_transformers :refer [SentenceTransformer]]
                '[sentence_transformers.util :refer [community_detection]])

(def model
  "sentence-transformers/multi-qa-MiniLM-L6-cos-v1")

(def tf
  #_:clj-kondo/ignore
  (delay (SentenceTransformer model)))

(def ^:dynamic *batch-size*
  64)

(defn encode
  [sentences]
  (py/py. @tf encode (py/->py-list sentences)
          :show_progress_bar false
          :convert_to_tensor true
          :batch_size *batch-size*))

(def ^:dynamic *min-community-size*
  2)

(def ^:dynamic *similarity-threshold*
  0.75)

(defn detect-communities
  [embeddings]
  #_:clj-kondo/ignore
  (community_detection embeddings
                       :min_community_size *min-community-size*
                       :threshold *similarity-threshold*
                       :batch_size *batch-size*))
