(ns zdl.nlp.fixture-test
  (:require
   [clojure.test :refer [deftest is]]
   [zdl.nlp.fixture.political-speeches :as pol-speech]))

(deftest parse-corpus
  (is (every? map? (pol-speech/docs))))
