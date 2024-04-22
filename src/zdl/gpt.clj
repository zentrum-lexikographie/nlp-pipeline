(ns zdl.gpt
  (:require
   [lambdaisland.uri :as uri]
   [jsonista.core :as json]
   [hato.client :as hc]
   [zdl.env :as env]))

(defn parse-choice
  [{{:keys [content]} :message :as choice}]
  (-> choice
      (dissoc :index :message)
      (assoc :message content)
      (update :finish_reason keyword)))

(defn parse-completion-response
  [{{:keys [choices usage]} :body :keys [request-time]}]
  {:request-time request-time
   :tokens       {:total      (get usage :total_tokens)
                  :prompt     (get usage :prompt_tokens)
                  :completion (get usage :completion_tokens)}
   :choices      (into [] (map parse-choice) choices)})

(defn chat
  [url model auth-token messages & {:as opts}]
  (-> {:method  :post
       :version :http-1.1
       :url     (str (uri/join url "chat/completions"))
       :headers (cond-> {"Content-Type" "application/json"
                         "Accept"       "application/json"}
                  auth-token (assoc "Authorization" (str "Bearer " auth-token)))
       :body    (json/write-value-as-string
                 (merge {:model       model
                         :temperature 0.0
                         :top_p       0.75
                         :messages    messages}
                        opts))}
      (hc/request)
      (update :body #(json/read-value % json/keyword-keys-object-mapper))
      (parse-completion-response)
      (assoc :messages messages :model model :url url)))

(def discolm
  (partial chat env/discolm-url env/discolm-model env/discolm-auth-token))

(def openai
  (partial chat env/openai-url env/openai-model env/openai-auth-token))

(comment
  (discolm [{:role    "system"
             :content "Du bist ein Lexikograph und gibst kurze, genaue Antworten!"}
            {:role    "user"
             :content "Hallo! Wie geht's?"}]))
