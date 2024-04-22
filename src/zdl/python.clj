(ns zdl.python
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [taoensso.timbre :as log]
   [zdl.process :as process]
   [zdl.env :as env]))

(def virtual-env-python-exe
  (io/file env/python-venv-dir "bin" "python"))

(def virtual-env-activate
  (io/file env/python-venv-dir "bin" "activate"))

(defn virtual-env-run!!
  [cmd]
  (let [cmd (str "source " (str virtual-env-activate) " && " cmd)]
    (process/run!! ["/bin/bash" "-c" cmd])))

(defn pip-install
  [reqs]
  (let [resource     (str "zdl/python/requirements." reqs ".txt")
        requirements (-> (io/resource resource) (slurp) (str/split #"\n"))
        requirements (str/join \space (map #(str "'" % "'") requirements))]
    (virtual-env-run!! (str "pip install " requirements))))

(when-not (.exists virtual-env-python-exe)
  (log/infof "Setting up Python virtual environment")
  (process/run!! ["python" "-m" "venv" (str env/python-venv-dir)])
  (virtual-env-run!! "python -m pip install -U pip")
  (pip-install "base")
  (when-not env/spacy-disable-model-download?
    (pip-install (if env/spacy-gpu? "gpu" "cpu"))))

(require '[libpython-clj2.python :as py])
(py/initialize! :python-executable (str virtual-env-python-exe))

(defn setup!
  [_]
  (System/exit 0))
