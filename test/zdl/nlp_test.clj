(ns zdl.nlp-test
  (:require [clojure.test :refer [deftest is]]
            [gremid.xml :as gxml]
            [zdl.nlp :as nlp]
            [taoensso.timbre :as log]))

(def input
  (->>
   [:TEI {:xmlns "http://www.tei-c.org/ns/1.0"}
    [:teiHeader
     [:fileDesc
      [:titleStmt
       [:title {:type "main"} "Test"]]]]
    [:text
     [:body
      [:head "Das ist ein Titel."]
      [:note {:type "remark"} "Eine Anmerkung, die nicht annotiert wird."]
      [:p "Das ist ein Satz, dessen Inhalt annotiert werden soll."]]]]
   (gxml/sexp->node)
   (gxml/write-node *out*)
   (with-out-str)))

(def config
  {:dwdsmor   true
   :simplemma true
   :spacy     true
   :lingua    true})

(deftest sample
  (let [sources  [(java.io.StringReader. input)]
        target   (java.io.StringWriter.)
        success? (nlp/tag config sources target)]
    (log/info (str target))
    (is success?)))
