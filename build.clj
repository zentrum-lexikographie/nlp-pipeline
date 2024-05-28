(ns build
  (:require
   [clojure.java.io :as io]
   [clojure.tools.build.api :as b]
   [clojure.tools.build.tasks.copy :refer [default-ignores]]
   [taoensso.timbre :as log])
  (:import
   (java.lang ProcessBuilder$Redirect)))

(defn check!
  [^Process proc]
  (.waitFor ^Process proc)
  (let [exit-status (.exitValue proc)]
    (when-not (zero? exit-status)
      (throw (ex-info (str "Error executing command: " exit-status)
                      {:proc proc})))))

(defn proc!
  ([cmd]
   (proc! cmd "."))
  ([cmd dir]
   (log/debugf "[@ %s] ! %s" dir cmd)
   (.. (ProcessBuilder. (into-array String cmd))
       (directory (io/file dir))
       (redirectInput ProcessBuilder$Redirect/INHERIT)
       (redirectOutput ProcessBuilder$Redirect/INHERIT)
       (redirectError ProcessBuilder$Redirect/INHERIT)
       (start))))

(def check-proc!
  (comp check! proc!))

(def temp-dir
  (io/file (System/getProperty "java.io.tmpdir")))

(def dwdsmor-resources
  (io/file "resources" "dwdsmor"))

(def dwdsmor-automaton
  (io/file dwdsmor-resources "dwdsmor.hfst"))

(defn transpile-dwdsmor-automaton
  [& _]
  (let [sfst-automaton (io/file dwdsmor-resources "dwdsmor.a")
        hfst-inverted  (io/file temp-dir "dwdsmor.inv.hfst")]
    (check-proc! ["hfst-invert"
                  "-i" (str sfst-automaton)
                  "-o" (str hfst-inverted)])
    (check-proc! ["hfst-fst2fst" "-O"
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

(defn dwdsmor-coverage-jar
  [& _]
  (let [basis        (b/create-basis {:project "deps.edn" :aliases #{:hfst}})
        classes      (str (io/file "classes" "dwdsmor-coverage"))
        hfst-classes (str (io/file "classes" "hfst"))
        jar          (str (io/file "jars" "zdl.nlp.dwdsmor.coverage.jar"))
        main         'zdl.nlp.dwdsmor.coverage]
    (b/delete {:path jar})
    (b/delete {:path classes})
    (compile-hfst)
    (b/copy-dir {:src-dirs [hfst-classes] :target-dir classes})
    (b/compile-clj {:basis     basis   :ns-compile [main]
                    :class-dir classes :src-dirs   ["src"]})
    (b/uber {:class-dir classes :basis basis :uber-file jar :main main})))

(def clj-ignores
  (conj default-ignores #".*\.clj$"))

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
