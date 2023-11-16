(ns zdl.nlp
  (:require
   [zdl.nlp.annotation :as anno]
   [zdl.nlp.tagger :refer [combined dwdsmor flair spacy tag!]]
   [zdl.nlp.tokenizer :refer [tokenize]]))

(defn -main
  [& _]
  (try
    (let [tagger (combined [(spacy) (flair) (dwdsmor)])
          tokens (tokenize (slurp *in*))]
      (reduce
       (fn [_ result] (println (pr-str (anno/process result))))
       nil
       (tag! tagger tokens)))
    (finally
      (shutdown-agents))))
