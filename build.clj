(ns build
  (:require
   [babashka.process :refer [check process]]
   [clojure.java.io :as io]
   [clojure.tools.build.api :as b]
   [clojure.tools.build.tasks.copy :refer [default-ignores]]
   [taoensso.timbre :as log]))

(def temp-dir
  (io/file (System/getProperty "java.io.tmpdir")))

(def dwdsmor-resources
  (io/file "resources" "dwdsmor"))

(def dwdsmor-automaton
  (io/file dwdsmor-resources "dwdsmor.hfst"))

(def check-proc
  (comp check process))

(defn transpile-dwdsmor-automaton
  [& _]
  (let [sfst-automaton (io/file dwdsmor-resources "dwdsmor.a")
        hfst-inverted  (io/file temp-dir "dwdsmor.inv.hfst")]
    (check-proc ["hfst-invert"
                 "-i" (str sfst-automaton)
                 "-o" (str hfst-inverted)])
    (check-proc ["hfst-fst2fst" "-O"
                 "-i" (str hfst-inverted)
                 "-o" (str dwdsmor-automaton)])
    (log/infof "Transpiled DWDSmor/HFST automaton %s" (str dwdsmor-automaton))
    (io/delete-file hfst-inverted)))

(defn compile-hfst
  [& _]
  (let [basis   (b/create-basis {:project "deps.edn"})
        source  (str (io/file "java" "src"))
        classes (str (io/file "java" "classes"))]
    (b/delete {:path classes})
    (b/javac {:src-dirs  [source]
              :class-dir classes
              :basis     basis})))

(def clj-ignores
  (conj default-ignores #".*\.clj$"))

(defn coverage-jar
  [& _]
  (let [basis        (b/create-basis {:project "deps.edn"})
        src-dirs     ["src" "java/classes"]
        classes      (str (io/file "classes" "coverage"))
        jar          (str (io/file "jars" "zdl.nlp.coverage.jar"))
        main         'zdl.nlp.coverage]
    (b/delete {:path jar})
    (b/delete {:path classes})
    (b/copy-dir {:src-dirs   src-dirs
                 :target-dir classes
                 :ignores    clj-ignores})
    (b/compile-clj {:basis     basis   :ns-compile [main]
                    :class-dir classes :src-dirs   src-dirs})
    (b/uber {:class-dir classes :basis basis :uber-file jar :main main})))

(defn tei-corpus-backup-jar
  [& _]
  (let [basis    (b/create-basis {:project "deps.edn" :aliases #{:cli}})
        src-dirs ["src"]
        classes  (str (io/file "classes" "zdl.xml.tei.corpus.backup"))
        jar      (str (io/file "jars" "zdl.xml.tei.corpus.backup.jar"))
        main     'zdl.xml.tei.corpus.backup]
    (b/delete {:path jar})
    (b/delete {:path classes})
    (compile-hfst)
    (b/copy-dir {:src-dirs src-dirs :target-dir classes :ignores clj-ignores})
    (b/compile-clj {:basis     basis   :ns-compile [main]
                    :class-dir classes :src-dirs   src-dirs})
    (b/uber {:class-dir classes :basis basis :uber-file jar :main main})))

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
