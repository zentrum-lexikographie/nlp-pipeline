(ns user
  (:require
   [clojure.tools.namespace.repl :as repl :refer [set-refresh-dirs]]
   [zdl.nlp.lab :as lab]))

(set-refresh-dirs "dev" "src")

(defn start!
  []
  (lab/start!))

(def stop!
  nil)

(defn go
  []
  (alter-var-root #'stop! (constantly (start!))))

(defn halt
  []
  (when stop!
    (stop!)
    (alter-var-root #'stop! (constantly nil))))

(defn reset
  []
  (halt)
  (repl/refresh :after 'user/go))

(defn reset-all
  []
  (halt)
  (repl/refresh-all :after 'user/go))

(comment
  (go)
  (halt)
  (reset)
  (reset-all))

(comment
  (require '[nextjournal.clerk :as clerk])
  (clerk/serve! {:browse? true})
  (clerk/build! {:paths ["notebooks/simplemma_coverage.clj"]}))
