(ns zdl.nlp.index
  (:require [camel-snake-kebab.core :as csk]
            [camel-snake-kebab.extras :as cske]
            [hato.client :as hc]
            [jsonista.core :as json]
            [gremid.xml :as gxml]
            [zdl.nlp.folia :as folia]
            [zdl.nlp.xml :as xml]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def ->kebab-case
  (memoize csk/->kebab-case-keyword))

(def transform-keys->kebab-case
  (partial cske/transform-keys ->kebab-case))

;; ## Solr

(def solr-query-uri
  "http://localhost:8983/solr/zdl/select")

(def solr-update-uri
  "http://localhost:8983/solr/zdl/update")

(defn send-query!
  [params]
  (->
   {:method       :get
    :as           :stream
    :url          solr-query-uri
    :query-params (merge
                   {:wt "json"
                    :q  "*:*"
                    :df "id"}
                   params)}
   (hc/request)
   (update :body (comp transform-keys->kebab-case json/read-value))))


(def update-batch-size
  1000)

(defn send-update!
  [commands]
  (doseq [cmd-batch (partition-all update-batch-size commands)]
    (->
     {:method       :post
      :as           :stream
      :url          solr-update-uri
      :query-params {:wt "json"}
      :headers      {"Content-Type" "text/xml"}
      :body         (xml/xml->str (gxml/sexp->node [:update cmd-batch]))}
     (hc/request))))

(defn num-entries
  []
  (let [results (send-query! {:q "*:*" :rows 0})]
    (get-in results [:body "response" "numFound"] -1)))

(defn index-empty?
  []
  (zero? (num-entries)))

(defn clear!
  []
  (send-update! [[:delete [:query "*:*"]] [:commit]]))

;; ## MTAS

(defn mtas-cql
  [q]
  (str "{!mtas_cql field=\"text\" query=\""
       (-> q (str/replace #"\\" "\\\\\\\\" ) (str/replace #"\"" "\\\\\""))
       "\"}"))

(defn pos-range
  [{:keys [position-start position-end start-position end-position]}]
  [(or position-start start-position) (or position-end end-position)])

(defn pos-range-overlaps?
  [[as ae] [bs be]]
  (and (<= as be) (>= ae bs)))

(defn compare-pos-ranges
  [[as ae] [bs be]]
  (let [ds (- as bs)]
    (if (not= ds 0) ds (- be ae))))

(defn parse-mtas-token
  [hit-range token]
  (let [token-range (pos-range token)
        hit?        (pos-range-overlaps? hit-range token-range)]
    (-> token
        (assoc :range token-range :hit? hit?)
        (dissoc :position-start :position-end))))

(defn parse-kwic
  [{[{results :list}] :kwic}]
  (zipmap
   (map :document-key results)
   (for [result results]
     (for [doc-result (get result :list)]
       {:segment (let [result-range (pos-range doc-result)
                       tokens       (get doc-result :tokens)
                       tokens       (map #(parse-mtas-token result-range %) tokens)
                       tokens       (sort-by :range compare-pos-ranges tokens)
                       tokens       (partition-by :range tokens)]
                   tokens)}))))

(defn parse-mtas-response
  [{{:keys [mtas response]} :body :as resp}]
  (let [docs  (:docs response)
        kwic  (parse-kwic mtas)
        hits  (map #(assoc % :segments (get kwic (get % :id))) docs)]
    (assoc resp :hits hits)))

(defn build-mtas-query
  [fq q]
  {:q                       (mtas-cql q)
   :fq                      fq
   :rows                    "100"
   :mtas                    "true"
   :mtas.kwic               "true"
   :mtas.kwic.0.key         "kwic"
   :mtas.kwic.0.field       "text"
   :mtas.kwic.0.query.type  "cql"
   :mtas.kwic.0.query.value q
   :mtas.kwic.0.left        "10"
   :mtas.kwic.0.right       "10"})

(def send-mtas-query!
  (comp parse-mtas-response send-query!  build-mtas-query))

(comment
  (->>
   (read-string (slurp (io/file "sample-sentence.tagged.edn")))
   (map-indexed (fn [i s] (folia/sentence (str "s." (inc i)) s))))

  (clear!)
  (let [sentences (read-string (slurp (io/file "sample-sentence.tagged.edn")))
        sentences (map-indexed (fn [i s] (folia/sentence (str "s." (inc i)) s))
                               sentences)
        text      [:FoLiA {:xmlns "http://ilk.uvt.nl/folia"}
                   [:text [:p sentences]]]
        doc       [:doc
                   [:field {:name "id"} (str (random-uuid))]
                   [:field {:name "timestamp"} (System/currentTimeMillis)]
                   [:field {:name "title"} "ChatGPT und Turing"]
                   [:field {:name "date"} "2023-06-10T00:00:00Z"]
                   [:field {:name "date_range"} "2023-06"]
                   [:field {:name "text_type"} "folia"]
                   [:field {:name "text"} (xml/xml->str (gxml/sexp->node text))]]]
    (send-update! [[:delete [:query "*:*"]] [:add doc] [:commit]]))

  (send-query! {:mtas                "true"
                :mtas.version        "true"
                :mtas.prefix         "true"
                :mtas.prefix.0.field "text"
                :mtas.prefix.0.key   "prefixes"})

  (send-mtas-query! "title:chatgpt && date_range:[2023-01 TO 2024-01}"
                    "<ent=\"PER\"/>"))
