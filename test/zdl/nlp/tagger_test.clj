(ns zdl.nlp.tagger-test
  (:require
   [clojure.test :refer [deftest is]]
   [zdl.nlp.env :as env]
   [zdl.nlp.fixture.political-speeches :as pol-speech]
   [zdl.nlp.tagger :refer [tag->vec! combined parallel dwdsmor spacy flair]]))

(def config
  (env/tagger-config {:dwdsmor          true
                      :dwdsmor-parallel 2
                      :simplemma        true
                      :spacy            true
                      :flair            true
                      :lingua           true}))

(defn texts
  []
  (into []
        (comp (take 5) (map #(array-map :text %)))
        (pol-speech/texts)))

(defn tagger
  []
  (combined (parallel (repeatedly 2 dwdsmor))
            (spacy)
            (flair)))

(deftest tag-texts
  (let [texts (texts)]
    (is (= (count texts) (count (tag->vec! (tagger) texts))))))
