(ns zdl.process
  (:refer-clojure :exclude [run!])
  (:require [taoensso.timbre :as log]
            [clojure.java.io :as io])
  (:import
   (java.lang ProcessBuilder$Redirect)))

(defn check!
  [^Process proc]
  (.waitFor ^Process proc)
  (let [exit-status (.exitValue proc)]
    (when-not (zero? exit-status)
      (throw (ex-info (str "Error executing command: " exit-status)
                      {:proc proc})))))

(defn run!
  ([cmd]
   (run! cmd "."))
  ([cmd dir]
   (log/debugf "[@ %s] ! %s" dir cmd)
   (.. (ProcessBuilder. (into-array String cmd))
       (directory (io/file dir))
       (redirectInput ProcessBuilder$Redirect/INHERIT)
       (redirectOutput ProcessBuilder$Redirect/INHERIT)
       (redirectError ProcessBuilder$Redirect/INHERIT)
       (start))))

(def run!!
  (comp check! run!))
