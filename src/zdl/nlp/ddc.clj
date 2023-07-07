(ns zdl.nlp.ddc
  (:require
   [clojure.core.async :as a]
   [clojure.string :as str]
   [jsonista.core :as json]
   [taoensso.timbre :as log])
  (:import
   (java.net InetSocketAddress)
   (java.nio.charset Charset)
   (java.nio ByteBuffer ByteOrder)
   (java.nio.channels AsynchronousSocketChannel CompletionHandler)
   (java.time Duration)
   (java.util.concurrent TimeUnit)))

(defn completion-handler
  [success ch]
  (let [complete! #(a/onto-chan! ch (list %))]
    (proxy [CompletionHandler] []
      (completed [v _] (complete! (success v)))
      (failed    [t _] (complete! {::error t})))))

(defn successful
  [{::keys [error] :as result}]
  (when error (throw error))
  result)

(defn ddc-endpoint
  [{::keys [host port]}]
  (format "[DDC %s:%d/tcp]" host port))

(defn connect!
  [{::keys [host port] :as ctx}]
  (log/tracef "%s : connecting" (ddc-endpoint ctx))
  (let [client (AsynchronousSocketChannel/open)
        addr   (InetSocketAddress. ^String host ^Integer port)
        ch     (a/chan)]
    (->>
     (completion-handler (constantly (assoc ctx ::client client)) ch)
     (.connect ^AsynchronousSocketChannel client addr nil))
    ch))

(def ^Charset charset
  (Charset/forName "UTF-8"))

(defn send!
  [{::keys [client] :as ctx} cmd]
  (log/tracef "%s : sending command %s" (ddc-endpoint ctx) (pr-str cmd))
  (let [ch      (a/chan)
        payload (.getBytes ^String cmd charset)
        len     (count payload)
        buf     (.. (ByteBuffer/allocate (+ 4 len))
                    (order ByteOrder/LITTLE_ENDIAN)
                    (putInt (unchecked-int len))
                    (put (ByteBuffer/wrap payload))
                    (flip))]
    (->>
     (completion-handler (constantly ctx) ch)
     (.write ^AsynchronousSocketChannel client buf nil))
    ch))

(def default-read-timeout
  (Duration/ofSeconds 30))

(defn read-from-socket!
  [client buf timeout ch]
  (.read ^AsynchronousSocketChannel client ^ByteBuffer buf
         (.toMillis ^Duration timeout) TimeUnit/MILLISECONDS
         nil ^CompletionHandler ch))

(defn read!
  [{::keys [client timeout] :or {timeout default-read-timeout}} ^ByteBuffer buf]
  (a/go-loop []
    (when (pos? (.remaining buf))
      (let [ch (a/chan)]
        (read-from-socket! client buf timeout (completion-handler identity ch))
        (let [result (a/<! ch)]
          (cond
            (::error result) result
            (neg? result)    buf
            :else            (recur)))))))

(defn buf->len
  [^ByteBuffer buf & _]
  (.. buf (order ByteOrder/LITTLE_ENDIAN) (getInt)))

(defn receive-len!
  [ctx]
  (log/tracef "%s : reading response length" (ddc-endpoint ctx))
  (a/go
    (let [buf (ByteBuffer/allocate 4)]
      (try
        (successful (a/<! (read! ctx buf)))
        (let [len (.. ^ByteBuffer buf (flip) (order ByteOrder/LITTLE_ENDIAN) (getInt))]
          (assoc ctx ::len len))
        (catch Throwable t t)))))

(defn receive!
  [{::keys [len] :as ctx}]
  (log/tracef "%s : reading response of %d byte(s)" (ddc-endpoint ctx) len)
  (a/go
    (let [buf (ByteBuffer/allocate len)
          ctx (dissoc ctx ::len)]
      (try
        (successful (a/<! (read! ctx buf)))
        (let [result (String. (.array ^ByteBuffer buf) charset)]
          (if (str/starts-with? result "{")
            (assoc ctx ::result (json/read-value result))
            (assoc ctx ::error (ex-info result ctx))))
        (catch Throwable t t)))))

(defn close!
  [{::keys [client] :as ctx}]
  (log/tracef "%s : closing connection %s" (ddc-endpoint ctx) client)
  (try
    (.close ^AsynchronousSocketChannel client)
    (catch Throwable t
      (log/tracef t "Error while closing connection of %s" ctx))))


(defn request!
  [host port cmd]
  (a/go
    (try
      (let [ctx {::host host ::port port ::cmd cmd}
            ctx (successful (a/<! (connect! ctx)))]
        (try
          (let [ctx (successful (a/<! (send! ctx cmd)))
                ctx (successful (a/<! (receive-len! ctx)))
                ctx (successful (a/<! (receive! ctx)))]
            (dissoc ctx ::client))
          (finally
            (close! ctx))))
      (catch Throwable t
        {::host host ::port port ::cmd cmd ::error t}))))

(defn encode-query
  "Encodes a DDC query command"
  [q & {:keys [offset limit timeout] :or {offset 0 limit 10 timeout 5}}]
  (assert (not-empty q) "No query (q) given")
  (->> (str/join " " [offset limit timeout])
       (vector "run_query Distributed" q "json")
       (str/join \)))

(defn sync-request!
  [& args]
  (let [result (a/<!! (apply request! args))]
    (when-let [error (::error result)]
      (throw (ex-info "DDC error" result error)))
    (::result result)))


(comment
  (sync-request! "data.dwds.de" 52710 "info")
  (time (sync-request! "data.dwds.de" 55020 (encode-query "Haus #separate" :limit 100))))


