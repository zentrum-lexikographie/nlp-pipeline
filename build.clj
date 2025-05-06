(ns build
  (:require
   [clojure.java.io :as io]
   [clojure.tools.build.api :as b]))

(defn compile-java
  [& _]
  (let [basis   (b/create-basis {:project "deps.edn"})
        source  (str (io/file "src"))
        classes (str (io/file "target/classes"))]
    (b/delete {:path classes})
    (b/javac {:src-dirs  [source]
              :class-dir classes
              :basis     basis})))
