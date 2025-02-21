(ns build
  (:require
   [clojure.java.io :as io]
   [clojure.tools.build.api :as b]
   [clojure.tools.build.tasks.copy :refer [default-ignores]]))

(defn compile-java
  [& _]
  (let [basis   (b/create-basis {:project "deps.edn"})
        source  (str (io/file "src" "main" "java"))
        classes (str (io/file "target" "classes"))]
    (b/delete {:path classes})
    (b/javac {:src-dirs  [source]
              :class-dir classes
              :basis     basis})))

(def clj-ignores
  (conj default-ignores #".*\.clj$"))

(defn jar
  [& _]
  (let [basis    (b/create-basis {:project "deps.edn"})
        src-dirs ["src"]
        classes  (str (io/file "classes" "jar"))
        jar      (str (io/file "jars" "zdl.nlp.jar"))
        main     'zdl.cli]
    (b/delete {:path jar})
    (b/delete {:path classes})
    (b/copy-dir {:src-dirs   src-dirs
                 :target-dir classes
                 :ignores    clj-ignores})
    (b/compile-clj {:basis      basis
                    :ns-compile [main]
                    :class-dir  classes
                    :src-dirs   src-dirs})
    (b/uber {:class-dir classes
             :basis     basis
             :uber-file jar
             :main      main})))
