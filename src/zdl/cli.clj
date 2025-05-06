(ns zdl.cli
  (:gen-class)
  (:require
   [babashka.cli :as cli]))

(def spec
  {})

(defn -main
  [& args]
  (println (cli/parse-opts args spec)))
