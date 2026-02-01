(ns zdl.gpt-gateway-test
  (:require
   [clojure.test :refer [deftest is use-fixtures]]
   [integrant.core :as ig]
   [jsonista.core :as json]
   [taoensso.telemere :as tm]
   [zdl.gpt-gateway :as gpt-gw])
  (:import
   (com.rabbitmq.client AMQP$BasicProperties$Builder CancelCallback DeliverCallback)))

(def ^:dynamic *system*)

(defn system
  [f]
  (let [system (ig/init gpt-gw/config)]
    (try
      (binding [*system* system]
        (f))
      (finally
        (ig/halt! system)))))

(use-fixtures :once system)

(deftest request-response
  (with-open [connection (gpt-gw/connect)
              channel    (. connection (createChannel))]
    (let [correlation-id (str (random-uuid))
          reply-to       (.. channel (queueDeclare) (getQueue))
          request-props  (.. (AMQP$BasicProperties$Builder.)
                             (correlationId correlation-id)
                             (replyTo reply-to)
                             (build))
          request        (json/write-value-as-bytes
                          {"messages" [{"role"    "user"
                                        "content" "Welchen Stellenwert hat KI?"}]})
          response       (promise)
          on-delivery    (reify DeliverCallback
                           (handle [_this _consumer-tag delivery]
                             (deliver response (.. delivery (getBody)))))
          on-cancel      (reify CancelCallback (handle [_this _consumer-tag]))
          consumer-tag   (.basicConsume channel reply-to true on-delivery on-cancel)]
      (try
        (.. channel (basicPublish "" gpt-gw/queue-name request-props request))
        (let [response @response]
          (tm/log! {:id    ::response
                    :level :debug
                    :data  (json/read-value response)})
          (is (some? response)))
        (finally
          (.. channel (basicCancel consumer-tag)))))))
