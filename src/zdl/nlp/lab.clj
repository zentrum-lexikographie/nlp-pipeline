(ns zdl.nlp.lab
  (:require
   [clojure.string :as str]
   [muuntaja.core :as m]
   [reitit.coercion.malli]
   [reitit.ring]
   [reitit.ring.coercion]
   [reitit.ring.middleware.exception]
   [reitit.ring.middleware.muuntaja]
   [reitit.ring.middleware.parameters]
   [ring.util.response :as resp]
   [taoensso.timbre :as log]
   [zdl.env :as env]
   [zdl.nlp.html :as html])
  (:import
   (org.eclipse.jetty.server Server)))

(defn proxy-headers->request
  [{:keys [headers] :as request}]
  (let [scheme      (some->
                     (or (headers "x-forwarded-proto") (headers "x-scheme"))
                     (str/lower-case) (keyword) #{:http :https})
        remote-addr (some->>
                     (headers "x-forwarded-for") (re-find #"^[^,]*")
                     (str/trim) (not-empty))]
    (cond-> request
      scheme      (assoc :scheme scheme)
      remote-addr (assoc :remote-addr remote-addr))))

(def proxy-headers-middleware
  {:name ::proxy-headers
   :wrap (fn [handler]
           (fn
             ([request]
              (handler (proxy-headers->request request)))
             ([request respond raise]
              (handler (proxy-headers->request request) respond raise))))})

(defn log-exceptions
  [handler ^Throwable e request]
  (when-not (some-> e ex-data :type #{:reitit.ring/response})
    (log/warn e (.getMessage e)))
  (handler e request))

(def exception-middleware
  (-> reitit.ring.middleware.exception/default-handlers
      (assoc :reitit.ring.middleware.exception/wrap log-exceptions)
      (reitit.ring.middleware.exception/create-exception-middleware)))

(def handler-options
  {:muuntaja   m/instance
   :coercion   reitit.coercion.malli/coercion
   :middleware [proxy-headers-middleware
                reitit.ring.middleware.parameters/parameters-middleware
                reitit.ring.middleware.muuntaja/format-middleware
                exception-middleware
                reitit.ring.coercion/coerce-exceptions-middleware
                reitit.ring.coercion/coerce-request-middleware
                reitit.ring.coercion/coerce-response-middleware]})

(def index-handler
  {:name    :index
   :handler (-> (resp/response html/index)
                (resp/content-type "text/html")
                (constantly))})

(def ring-handler
  (reitit.ring/ring-handler
   (reitit.ring/router
    [env/http-context-path handler-options
     [["/" index-handler]]])
   (reitit.ring/routes
    (reitit.ring/redirect-trailing-slash-handler)
    (reitit.ring/create-file-handler {:path env/http-context-path})
    (reitit.ring/create-default-handler))))

(defn stop!
  [^Server server]
  (.stop server)
  (.join server))

(require 'ring.adapter.jetty)

(defn start!
  [& _]
  (log/infof "Starting HTTP server @ %d/tcp" env/http-port)
  (->> {:port               env/http-port
        :output-buffer-size 1024
        :join?              false}
       (ring.adapter.jetty/run-jetty ring-handler)
       (partial stop!)))
