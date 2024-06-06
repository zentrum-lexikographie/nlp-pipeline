(ns zdl.nlp.simplemma.lexicon
  {:clj-kondo/config '{:linters {:unresolved-symbol    {:level :off}
                                 :unresolved-namespace {:level :off}}}}
  (:require
   [babashka.fs :as fs]
   [babashka.process :refer [check process]]
   [clojure.java.io :as io]
   [taoensso.timbre :as log]
   [zdl.util :refer [pr-edn]])
  (:import
   (java.util.zip GZIPOutputStream)))

(log/merge-config!
 {:min-level [["libpython-clj2.*" :warn]
              ["tech.v3.*" :warn]
              ["*" :debug]]})

(Thread/setDefaultUncaughtExceptionHandler
 (reify Thread$UncaughtExceptionHandler
   (uncaughtException [_ thread ex]
     (log/errorf ex "Uncaught exception on [%s]." (.getName thread)))))

(require '[libpython-clj2.python :as py]
          '[libpython-clj2.require :refer [require-python]])

(require-python 'lzma 'pickle)

(def git-url
  "https://github.com/adbar/simplemma.git")

(def edn-file
  (fs/file "src" "zdl" "nlp" "simplemma" "lexicon.edn.gz"))

(defn clone-repo
  [dir]
  (->>
   {:cmd ["git" "clone" "-q" "--depth" "1" git-url (str dir)]}
   process check))

(defn decode-str
  [s]
  (py/py. s decode "utf-8"))

(defn read-lexicon
  [dir lang]
  (let [f (fs/path dir "simplemma" "strategies" "dictionaries" "data"
                   (str lang ".plzma"))]
    (log/infof "Reading simplemma lexicon %s'" f)
    (py/with
     [fh (lzma/open (str f))]
     (->> (for [[k v] (pickle/load fh)] [(decode-str k) (decode-str v)])
          (into {})))))

(defn import!
  [& _]
  (let [dir (fs/create-temp-dir)]
    (try
      (log/infof "Cloning simplemma repository to %s" (str dir))
      (clone-repo dir)
      (let [lexicon (into {}
                          (map (juxt identity (partial read-lexicon dir)))
                          ["de" "en" "fr"])]
        (log/infof "Writing %,d entries to %s"
                   (count (mapcat keys (vals lexicon)))
                   (str edn-file))
        (with-open [w (-> (io/output-stream edn-file)
                          (GZIPOutputStream.)
                          (io/writer))]
          (binding [*out* w] (pr-edn lexicon))))
      (finally
        (fs/delete-tree dir)))))
