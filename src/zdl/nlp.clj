(ns zdl.nlp
  (:require
   [zdl.nlp.deps :as deps]
   [zdl.nlp.dwdsmor :as dwdsmor]
   [zdl.nlp.gdex :as gdex]
   [zdl.nlp.hash :as hash]
   [zdl.nlp.langid :as langid]
   [zdl.nlp.spacy :as spacy]))


(def annotate
  (comp (partial pmap (comp deps/analyze-collocations
                            hash/fingerprint
                            gdex/score
                            dwdsmor/lemmatize
                            langid/detect-lang))
        spacy/tagged-seq))
