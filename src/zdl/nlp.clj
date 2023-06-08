(ns zdl.nlp
  (:require
   [zdl.nlp.tokenizer :refer [tokenize]]
   [zdl.nlp.annotation :as anno]
   [zdl.nlp.tagger :refer [with-tagger combined spacy dwdsmor flair]]))

(defn -main
  [& _]
  (try
    #_:clj-kondo/ignore
    (with-tagger
      [tagged (combined [(spacy) (flair) (dwdsmor)])]
      (tokenize (slurp *in*))
      (run! (comp println pr-str) (pmap anno/process tagged)))
    (finally
      (shutdown-agents))))
