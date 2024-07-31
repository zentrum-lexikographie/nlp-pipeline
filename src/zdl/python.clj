(ns zdl.python
  (:refer-clojure :exclude [run!])
  (:require
   [babashka.fs :as fs]
   [babashka.process :refer [check process]]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [libpython-clj2.python :as py]
   [taoensso.timbre :as log]
   [zdl.env :as env]))

(defn proc!
  [cmd]
  (check (process {:cmd cmd :out :string :err :string})))

(def venv-dir
  env/python-venv-dir)

(def venv-python-exe
  (fs/file venv-dir "bin" "python"))

(def venv-activate
  (fs/file venv-dir "bin" "activate"))

(defn venv-cmd
  [cmd]
  ["/bin/bash" "-c" (str "source " (str venv-activate) " && " cmd)])

(when-not (fs/exists? venv-python-exe)
  (log/infof "Setting up Python virtual environment @ %s" venv-dir)
  (proc! ["python" "-m" "venv" (str venv-dir)])
  (proc! (venv-cmd "python -m pip install -U pip")))

(py/initialize! :python-executable (str venv-python-exe))

(defn run!
  [cmd]
  (log/debugf "! %s" cmd)
  (proc! (venv-cmd cmd)))

(defn python!
  [args]
  (run! (str "python " args)))

(defn installed?
  [module]
  (try
    (python! (str "-c 'import " module "'"))
    true
    (catch Throwable t
      (when-not (some-> t ex-data :exit pos?)
        (throw t))
      false)))

(defn install!
  [& reqs]
  (doseq [req reqs] (log/infof "Installing requirement '%s'" req))
  (->> (str/join \space (map #(str "'" % "'") reqs))
       (str "pip install ")
       (run!)))

(defn install-reqs!
  [input]
  (with-open [r (io/reader input)] (install! (line-seq r))))
