(ns zdl.log
  (:require
   [taoensso.timbre :as log]))

(defn configure!
  [debug?]
  (log/handle-uncaught-jvm-exceptions!)
  (log/merge-config!
   {:min-level (if debug? :trace :debug)
    :appenders {:println (log/println-appender {:stream :std-err})}}))
