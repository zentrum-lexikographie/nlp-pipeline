(ns zdl.nlp.mtas
  (:require
   [clojure.string :as str]
   [gremid.xml :as gxml]
   [hato.client :as hc]
   [charred.api :as charred]
   [zdl.xml :as xml]
   [clojure.data.csv :as csv]
   [zdl.nlp.gdex :as gdex]))

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
    :url          solr-query-uri
    :query-params (merge
                   {"wt" "json"
                    "q"  "*:*"
                    "df" "id"}
                   params)}
   (hc/request)
   (update :body charred/read-json)))


(def update-batch-size
  1000)

(defn send-update!
  [commands]
  (doseq [cmd-batch (partition-all update-batch-size commands)]
    (->
     {:method       :post
      :version      :http-1.1
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
   "rows"                    "10"
   "mtas"                    "true"
   "mtas.kwic"               "true"
   "mtas.kwic.0.key"         "kwic"
   "mtas.kwic.0.field"       "text"
   "mtas.kwic.0.query.type"  "cql"
   "mtas.kwic.0.query.value" q
   "mtas.kwic.0.output"      "hit"})

(def send-mtas-query!
  (comp #_parse-mtas-response send-query! build-mtas-query))

(def ^:dynamic *id*
  (volatile! -1))

(defn next-id
  []
  (vswap! *id* inc))

(defn token->csv
  [s-id position {:keys [n form lemma space-after? xpos upos]}]
  (let [pos (+ position n)]
    (->>
     (cond-> [[(next-id) "t" form s-id pos pos]]
       lemma                  (conj [(next-id) "l" lemma s-id pos pos])
       upos                   (conj [(next-id) "upos" upos s-id pos pos])
       xpos                   (conj [(next-id) "xpos" xpos s-id pos pos])
       (= false space-after?) (conj [(next-id) "ws" "0" s-id pos pos])))))

(defn deps->csv
  [s-id position tokens [root children]]
  (let [rp (+ position root)]
    (mapcat
     (fn [child]
       (let [cp      (+ position child)
             dep-id  (next-id)
             dep-rel (-> child tokens :deprel)]
         (list [dep-id "dep" dep-rel s-id rp rp cp cp]
               [(next-id) "dep.hd" dep-rel dep-id rp rp]
               [(next-id) "dep.cd" dep-rel dep-id cp cp])))
     children)))

(def span-type->prefix
  {:collocation "colloc"
   :entity      "entity"})

(defn span->csv
  [s-id position {:keys [type label targets]}]
  (when-let [prefix (span-type->prefix type)]
    (->> (map #(+ position %) targets)
         (mapcat #(list % %))
         (into [(next-id) prefix label s-id])
         (list))))

(defn sentence->csv
  [p-id position {:keys [tokens deps gdex spans]}]
  (let [s-id (next-id)
        start position
        end (+ position (dec (count tokens)))]
    (concat
     (list [s-id "s" "" p-id start end])
     (mapcat #(token->csv s-id position %) tokens)
     (mapcat #(deps->csv s-id position tokens %) deps)
     (mapcat #(span->csv s-id position %) spans)
     (when gdex
       (let [v (gdex/score->str gdex)]
         (cond->> (list [(next-id) "gdex-score" v s-id start end])
           (gdex/good? gdex) (cons [(next-id) "gdex" v s-id start end])))))))

(defn chunk->csv
  [d-id position {:keys [sentences]}]
  (let [c-id    (next-id)
        offsets (reduce #(conj %1 (+ (last %1) %2)) [position]
                        (map (comp count :tokens) sentences))
        start   position
        end     (+ start (dec (last offsets)))]
    (concat (list [c-id "p" "" d-id start end])
            (mapcat #(sentence->csv c-id %1 %2) offsets sentences))))

(defn doc->csv
  [{:keys [chunks]}]
  (binding [*id* (volatile! -1)]
    (let [d-id    (next-id)
          offsets (reduce #(conj %1 (+ (last %1) %2)) [0]
                          (map #(->> (:sentences %) (mapcat :tokens) (count))
                               chunks))
          start   0
          end     (dec (last offsets))]
      (into [[d-id "doc" "" 0 start end]]
            (mapcat #(chunk->csv d-id %1 %2) offsets chunks)))))

(defn csv->str
  [records]
  (with-out-str (csv/write-csv *out* records)))

(defn ->doc
  [doc]
  [:doc
   [:field {:name "id"} (str (random-uuid))]
   [:field {:name "timestamp"} (System/currentTimeMillis)]
   [:field {:name "title"} "Titel"]
   [:field {:name "date"} "2023-06-10T00:00:00Z"]
   [:field {:name "date_range"} "2023-06"]
   [:field {:name "text_type"} "csv"]
   [:field {:name "text"} (->> doc doc->csv csv->str)]])

(def ^:dynamic *index-batch-size*
  10)

(defn index
  [docs]
  (->> (map (comp (fn [doc] [:add doc]) ->doc) docs)
       (partition-all *index-batch-size*)
       (map send-update!)
       (last)))

(comment
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

  (send-update! [[:commit] [:optimize]])
  (send-query! {"mtas"                "true"
                "mtas.version"        "true"
                "mtas.prefix"         "true"
                "mtas.prefix.0.field" "text"
                "mtas.prefix.0.key"   "prefixes"})

  (-> 
   (send-mtas-query! "*:*" "[l=\"sollen\"][]")
   #_(get-in [:body "response" "numFound"])
   (time)))
