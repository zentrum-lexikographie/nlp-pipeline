(ns zdl.korap
  (:require [hato.client :as hc]
            [charred.api :as charred]))

(def access-token
  "1IyYcVj9pXsKpPUKCn2BEwAYo0yba12rAPpX1ecs46Wg")

(comment
  (->
   {:method       :get
    :url          "https://korap.ids-mannheim.de/api/v1.0/search"
    :headers      {"authorization" (str "Bearer " access-token)}
    :query-params {"ql"           "annis"
                   "q"            "node & tt/p=/.+/ & #2 ->malt/d[func=\"SUBJ\"] #1"
                   "context"      "sentence"
                   "offset"       "0"
                   "count"        "50"
                   "show-snippet" "true"}}
   (hc/request)
   (update :body charred/read-json)
   #_(get-in [:body "meta"])))
