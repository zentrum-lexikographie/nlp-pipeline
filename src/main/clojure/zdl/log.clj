(ns zdl.log
  (:require
   [taoensso.timbre :as log]))

(defn configure!
  [debug?]
  (log/handle-uncaught-jvm-exceptions!)
  (log/merge-config!
   {:min-level (if debug? :trace :debug)
    :appenders {:println (log/println-appender {:stream :std-err})}}))

(defn update-throughput!
  [{:keys [chunks chars sentences tokens]} {:keys [text] sentences* :sentences}]
  {:chunks    (inc chunks)
   :chars     (+ chars (count text))
   :sentences (+ sentences (count sentences*))
   :tokens    (+ tokens (count (mapcat :tokens sentences*)))})

(defn avg-per-sec
  [secs n]
  (if (pos? secs) (float (/ n secs)) 0.0))

(def log-throughput-format
  "%,12d/%,10.2f <p> %,12d/%,10.2f <s> %,12d/%,10.2f <w> %,12d/%,10.2f <c>")

(defn log!
  [start stats]
  (let [millis      (- (System/currentTimeMillis) start)
        avg-per-sec (partial avg-per-sec (/ millis 1000))
        stats       (deref stats)
        chunks      (:chunks stats)
        chars       (:chars stats)
        sentences   (:sentences stats)
        tokens      (:tokens stats)]
    (log/infof log-throughput-format
               chunks    (avg-per-sec chunks)
               sentences (avg-per-sec sentences)
               tokens    (avg-per-sec tokens)
               chars     (avg-per-sec chars))))

(defn throughput-xf
  [rf]
  (let [start   (System/currentTimeMillis)
        next*   (volatile! (System/currentTimeMillis))
        log?    #(<=  @next* (System/currentTimeMillis))
        next!   #(vreset! next* (+ (System/currentTimeMillis) 10000))
        stats   (volatile! {:chunks 0 :chars 0 :sentences 0 :tokens 0})
        update! #(vswap! stats update-throughput! %)
        log!    (partial log! start stats)]
    (fn
      ([] (rf))
      ([result]
       (log!)
       (rf result))
      ([result c]
       (update! c)
       (when (log?) (log!) (next!))
       (rf result c)))))

