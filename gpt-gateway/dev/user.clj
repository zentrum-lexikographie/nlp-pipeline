(ns user
  (:require
   [integrant.repl :refer [go halt reset reset-all]]
   [zdl.gpt-gateway :as gpt-gw]))

(def config
  (assoc-in gpt-gw/config [::gpt-gw/service :exit-on-cancel?] false))

(integrant.repl/set-prep! (constantly config))

(comment
  (go)
  (halt)
  (reset)
  (reset-all))
