(ns build
  (:require
   [clojure.tools.build.api :as b]))

(defn top-lemma-jar
  [& _]
  (let [classes "classes"
        basis   (b/create-basis {:project "deps.edn" :aliases #{:sqlite}})
        main    'zdl.nlp.top-lemma]
    (b/delete {:path classes})
    (b/copy-dir {:src-dirs   ["src"]
                 :target-dir classes})
    (b/compile-clj {:basis      basis
                    :ns-compile [main]
                    :class-dir  classes
                    :src-dirs   ["src"]})
    (b/uber {:class-dir classes
             :basis     basis
             :uber-file "top-lemma.jar"
             :main      main})
    (b/delete {:path classes})))

