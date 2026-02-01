(ns user
  (:require
   [integrant.repl :refer [go halt reset reset-all]]
   [zdl.gpt-gateway :as gpt-gw]))

(integrant.repl/set-prep! (constantly gpt-gw/config))

(comment
  (go)
  (halt)
  (reset)
  (reset-all))
