(ns zdl.gpt-gateway
  (:require
   [clojure.string :as str]
   [integrant.core :as ig]
   [jsonista.core :as json]
   [org.httpkit.client :as hc]
   [taoensso.telemere :as tm])
  (:import
   (com.codahale.metrics Meter MetricRegistry Slf4jReporter Timer)
   (com.rabbitmq.client AMQP$BasicProperties$Builder CancelCallback Channel Connection ConnectionFactory DeliverCallback Delivery)
   (io.github.cdimascio.dotenv Dotenv)
   (java.util.concurrent TimeUnit)
   (org.httpkit.client ClientSslEngineFactory)))

(def ^Dotenv dot-env
  (.. Dotenv (configure) (filename ".env") (ignoreIfMissing) (load)))

(defn getenv
  ([k]
   (getenv k nil))
  ([k df]
   (let [k (str "ZDL_NLP_GPT_GW_" k)]
     (some-> (or (System/getenv k) (.get dot-env k) df) str/trim not-empty))))

(tm/uncaught->error!)
(tm/set-min-level! (if (getenv "DEBUG") :debug :info))
(tm/set-min-level! nil "com.rabbitmq.client.TrustEverythingTrustManager" :error)

(defn log-error
  [{:keys [error opts] :as response}]
  (if error
    (do (tm/error! {:id ::request :data opts} error) nil)
    response))

(defn json-response
  [{{:keys [content-type]} :headers :as response}]
  (when response
    (cond-> response
      (str/includes? "application/json" (or content-type ""))
      (update :body json/read-value))))

(def default-response-handler
  (comp json-response log-error))

(def api-url
  (getenv "API_URL" "https://zdl-gpu01.bbaw.de/api/"))

(def api-key
  (getenv "API_KEY" "abc123xyz"))

(defn request
  ([req]
   (request req default-response-handler))
  ([req f]
   (tm/log! {:id    ::request
             :level :debug
             :data  {:request req}})
   (-> (update req :url #(str api-url %))
       (assoc-in [:headers "Authorization"] (str "Bearer " api-key))
       (assoc :sslengine (ClientSslEngineFactory/trustAnybody) :keepalive -1)
       (hc/request f))))

(defn models
  []
  (some->>
   (some-> (request {:method :get :url "models"}) deref :body (get "data"))
   (into [] (map #(% "id")))))

(def model
  (getenv "MODEL" "llama33"))

(defn complete
  [completion-req f]
  (-> {:method  :post
       :url     "chat/completions"
       :headers {"Content-Type" "application/json"}
       :body    (json/write-value-as-string (assoc completion-req "model" model))}
      (request f)))

(defn qa
  [q]
  (-> {"messages" [{"role" "user" "content" q}]}
      (complete default-response-handler)
      deref (get-in [:body "choices" 0 "message" "content"])))

(def ^MetricRegistry metrics-registry
  (MetricRegistry.))

(def ^Timer completion-timer
  (.timer metrics-registry "gpt.completion"))

(def ^Meter completion-errors
  (.meter metrics-registry "gpt.completion.errors"))

(defn on-message
  [^Channel channel ^Delivery delivery]
  (let [delivery-tag   (.. delivery (getEnvelope) (getDeliveryTag))
        ack!           (fn [] (.. channel (basicAck delivery-tag false)))
        delivery-props (.. delivery (getProperties))
        correlation-id (.. delivery-props (getCorrelationId))
        reply-to       (.. delivery-props (getReplyTo))
        reply-props    (.. (AMQP$BasicProperties$Builder.)
                           (correlationId correlation-id)
                           (build))
        completion-time (.time completion-timer)]
    (try
      (tm/log! {:id    ::message
                :level :debug
                :data  {:mq delivery}})
      (complete
       (json/read-value (.. delivery (getBody)))
       (fn [{:keys [error body] :as resp}]
         (try
           (.close completion-time)
           (tm/log! {:id    ::response
                     :level :debug
                     :data  {:mq   delivery
                             :http resp}})
           (cond
             error (do (.mark completion-errors)
                       (tm/error! {:id   ::request
                                  :data {:mq   delivery
                                         :http resp}}
                                  error))
             body  (->>
                    (.getBytes ^String body "UTF-8")
                    (.basicPublish channel "" reply-to reply-props)))
           (finally
             (ack!)))))
      (catch Throwable t
        (tm/error! {:id   ::request
                    :data {:mq delivery}}
                   t)
        (ack!)))))

(defmethod ig/init-key ::metrics
  [_ _]
  (doto (.build (Slf4jReporter/forRegistry metrics-registry))
    (.start 1 TimeUnit/MINUTES)))

(defmethod ig/halt-key! ::metrics
  [_ ^Slf4jReporter reporter]
  (.close reporter))

(def ^String queue-name
  (getenv "QUEUE_GPT_QUEUE" "gpt"))

(defn connect ^Connection
  []
  (. (doto (ConnectionFactory.)
       (.setHost (getenv "QUEUE_HOST" "labor.dwds.de"))
       (.setPort (parse-long (getenv "QUEUE_PORT" "5671")))
       (.setUsername (getenv "QUEUE_USER" "lex"))
       (.setPassword (getenv "QUEUE_PASSWORD" "lex"))
       (.useSslProtocol)
       (.setConnectionTimeout 10000)
       (.setHandshakeTimeout 10000)
       (.setShutdownTimeout 10000))
     (newConnection)))

(defmethod ig/init-key ::service
  [_ {:keys [exit-on-cancel?]}]
  (let [connection (connect)
        channel    (.createChannel connection)
        service    {:connection connection :channel channel}]
    (doto channel
      (.queueDeclare queue-name true false false nil)
      (.basicQos 1)
      (.basicConsume
       queue-name false
       (reify DeliverCallback
         (handle [_this _consumer-tag delivery] (on-message channel delivery)))
       (reify CancelCallback
         (handle [_this _consumer-tag]
           (when exit-on-cancel? (System/exit 1))))))
    (tm/log! {:id ::start :level :info :data service})
    service))

(defmethod ig/halt-key! ::service
  [_ {:keys [connection channel] :as service}]
  (try
    (tm/log! {:id ::stop :level :info :data service})
    (.close ^Channel channel)
    (.close ^Connection connection)
    (catch Throwable t
      (tm/error! {:id ::stop :data service} t))))

(def config
  {::metrics {}
   ::service {:exit-on-cancel? false}})

(defn serve!
  [& _]
  (let [config (assoc-in config [::service :exit-on-cancel?] true)
        system (ig/init config)]
    (. (Runtime/getRuntime) (addShutdownHook (Thread. #(ig/halt! system)))))
  @(promise))
