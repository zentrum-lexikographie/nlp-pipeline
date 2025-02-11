(ns zdl.fs
  (:refer-clojure :exclude [resolve])
  (:require
   [clojure.java.io :as io])
  (:import
   (java.io File)
   (java.nio.file Files FileSystem FileSystems FileVisitOption Path Paths)
   (java.util.stream Stream)))

(defprotocol Coercions
  (^Path as-path [x] "Coerce argument to a path."))

(def empty-str-array
  (into-array String []))

(extend-protocol Coercions
  nil
  (as-path [_] nil)

  String
  (as-path [s] (Paths/get s empty-str-array))

  Path
  (as-path [p] p)

  File
  (as-path [f] (.toPath f)))

(defn resolve
  [^Path this ^Path other]
  (.resolve this other))

(defn path ^Path
  [x & args]
  (reduce #(resolve %1 (as-path %2)) (as-path x) args))

(defn normalize ^Path
  [& args]
  (.normalize ^Path (apply path args)))

(extend-protocol io/Coercions
  Path
  (as-file [p] (.toFile p))
  (as-url [p] (.toURL (.toUri p))))

(defn file?
  [f]
  (.isFile (io/file f)))

(defn directory?
  [f]
  (.isDirectory (io/file f)))

(def ^FileSystem file-system
  (FileSystems/getDefault))

(defn path-matcher-fn
  [^String pattern]
  (let [pm (.getPathMatcher file-system pattern)]
    (fn [p] (.matches pm (path p)))))

(def file-visit-options
  (into-array FileVisitOption []))

(defn stream->seq
  [^Stream stream]
  (iterator-seq (.iterator stream)))

(defn walk
  [dir]
  (stream->seq (Files/walk (path dir) file-visit-options)))

(def xml-path?
  (path-matcher-fn "glob:**/*.xml"))

(defn expand
  [f]
  (let [f (io/file f)]
    (if (file? f) (list f) (filter file? (map io/file (walk f))))))

(comment
  (filter (path-matcher-fn "glob:**/*.xml") (walk "test/zdl/nlp/fixture")))
