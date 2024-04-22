(ns zdl.xml.tei.corpus.backup
  (:gen-class)
  (:require
   [clojure.java.io :as io]
   [taoensso.timbre :as log]
   [zdl.fs :as fs]
   [zdl.log]
   [zdl.xml.tei.corpus :as corpus])
  (:import
   (java.nio.file LinkOption)
   (java.util.zip GZIPOutputStream)))

(defn subdirs
  [f]
  (filter fs/directory? (.listFiles (io/file f))))

(defn find-src-dirs
  [base]
  (->> (mapcat subdirs (subdirs base))
       (filter #(= "src" (.getName ^java.io.File %)))))

(def xml-path?
  (fs/path-matcher-fn "glob:**/*.xml"))

(def no-link-options
  (into-array LinkOption []))

(defn real-path
  [f]
  (. (fs/path f) (toRealPath no-link-options)))

(defn corpus-name
  [src-dir]
  (.. (io/file src-dir) (getParentFile) (getName)))

(defn aggregate
  [src-dir]
  (let [corpus  (corpus-name src-dir)
        sources (into (sorted-set)
                      (filter xml-path?)
                      (fs/expand (real-path src-dir)))
        target  (io/file "corpora" (format "%s.tei.xml.gz" corpus))]
    (try
      (io/make-parents target)
      (with-open [target (io/output-stream target)
                  target (GZIPOutputStream. target)]
        (corpus/aggregate sources target))
      (catch Throwable t
        (log/fatalf t "Error while aggregating %s" src-dir)
        (io/delete-file target true)))))

(defn excluded-source?
  [src-dir]
  (#{"web_2016d" "web_base" "web_ext"} (corpus-name src-dir)))

(defn -main
  [& _]
  (try
    (zdl.log/configure! false)
    (->> ["/local/home.local/ddc-dstar/dstar.local/corpora"
          "/local/home.local/ddc-dstar/dstar.local/corpora/genios"]
         (mapcat find-src-dirs)
         (remove excluded-source?)
         (pmap aggregate)
         (dorun))
    (finally
      (shutdown-agents))))
