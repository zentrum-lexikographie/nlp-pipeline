(ns zdl.nlp.mtas
  (:require
   [clojure.string :as str]
   [gremid.xml :as gxml]
   [hato.client :as hc]
   [jsonista.core :as json]
   [zdl.util :refer [slurp-edn]]
   [zdl.xml :as xml]
   [clojure.java.io :as io]
   [clojure.data.csv :as csv]))

;; ## Solr

(def solr-query-uri
  "http://localhost:8983/solr/zdl/select")

(def solr-update-uri
  "http://localhost:8983/solr/zdl/update")

(defn send-query!
  [params]
  (->
   {:method       :get
    :version      :http-1.1
    :as           :stream
    :url          solr-query-uri
    :query-params (merge
                   {"wt" "json"
                    "q"  "*:*"
                    "df" "id"}
                   params)}
   (hc/request)
   (update :body json/read-value)))


(def update-batch-size
  1000)

(defn send-update!
  [commands]
  (doseq [cmd-batch (partition-all update-batch-size commands)]
    (->
     {:method       :post
      :version      :http-1.1
      :as           :stream
      :url          solr-update-uri
      :query-params {"wt" "json"}
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
  (map
   list
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
  {"q"                       (mtas-cql q)
   "fq"                      fq
   "rows"                    "100"
   "mtas"                    "true"
   "mtas.kwic"               "true"
   "mtas.kwic.0.key"         "kwic"
   "mtas.kwic.0.field"       "text"
   "mtas.kwic.0.query.type"  "cql"
   "mtas.kwic.0.query.value" q
   "mtas.kwic.0.left"        "10"
   "mtas.kwic.0.right"       "10"})

(def send-mtas-query!
  (comp #_parse-mtas-response send-query! build-mtas-query))

(def ^:dynamic *anno-id*
  (volatile! 0))

(defn next-anno-id
  []
  (vswap! *anno-id* inc))

(defn token-annotations
  [s-id position {:keys [n form lemma space-after? xpos upos]}]
  (let [pos (+ position n)]
    (->>
     (cond-> [[(next-anno-id) "t" form s-id pos pos]]
       lemma                  (conj [(next-anno-id) "l" lemma s-id pos pos])
       upos                   (conj [(next-anno-id) "upos" upos s-id pos pos])
       xpos                   (conj [(next-anno-id) "xpos" xpos s-id pos pos])
       (= false space-after?) (conj [(next-anno-id) "ws" "0" s-id pos pos])))))

(defn deps-annotations
  [s-id position tokens [root children]]
  (let [root-position (+ position root)]
    (for [child children :let [child-position (+ position child)]]
      [(next-anno-id)
       "dep"
       (-> child tokens :deprel)
       s-id
       root-position root-position
       child-position child-position])))

(def span-type->prefix
  {:collocation "colloc"})

(defn span-annotations
  [s-id position {:keys [type label targets]}]
  (when-let [prefix (span-type->prefix type)]
    (->> (map #(+ position %) targets)
         (mapcat #(list % %))
         (into [(next-anno-id) prefix label s-id])
         (list))))

(defn sentence->annotations
  [p-id position {:keys [tokens deps gdex spans]}]
  (let [s-id (next-anno-id)
        start position
        end (+ position (dec (count tokens)))]
    (concat (list [s-id "s" "" p-id start end])
            (mapcat #(token-annotations s-id position %) tokens)
            (mapcat #(deps-annotations s-id position tokens %) deps)
            (mapcat #(span-annotations s-id position %) spans)
            (when gdex (list [(next-anno-id) "gdex" (str gdex) s-id start end])))))

(defn chunk->annotations
  [position {:keys [sentences]}]
  (let [c-id    (next-anno-id)
        offsets (reduce #(conj %1 (+ (last %1) %2)) [0]
                        (map (comp count :tokens) sentences))
        start   position
        end     (+ start (dec (count (mapcat :tokens sentences))))]
    (concat (list [c-id "p" "" 0 start end])
            (mapcat #(sentence->annotations c-id %1 %2) offsets sentences))))

(comment
  (defonce sample
    (slurp-edn (io/file "sample.edn")))
  
  (binding [*anno-id* (volatile! 0)]
    (->> sample (take 1000) (mapcat #(chunk->annotations 0 %1))
         (csv/write-csv *out*) (with-out-str) (count)))
  
  (clear!)
  (let [doc [:doc
             [:field {:name "id"} (str (random-uuid))]
             [:field {:name "timestamp"} (System/currentTimeMillis)]
             [:field {:name "title"} "ChatGPT und Turing"]
             [:field {:name "date"} "2023-06-10T00:00:00Z"]
             [:field {:name "date_range"} "2023-06"]
             [:field {:name "text_type"} "csv"]
             [:field {:name "text"} "0,t,Test,0,0,0\n1,t,Hallo,0,1,1"]]]
    (send-update! [[:delete [:query "*:*"]] [:add doc] [:commit]]))

  (send-query! {"mtas"                "true"
                "mtas.version"        "true"
                "mtas.prefix"         "true"
                "mtas.prefix.0.field" "text"
                "mtas.prefix.0.key"   "prefixes"})

  (send-mtas-query! "title:chatgpt && date_range:[2023-01 TO 2024-05}"
                    "<t=\"Ha..o\"/>"))
