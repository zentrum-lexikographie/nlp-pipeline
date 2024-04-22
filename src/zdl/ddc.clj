(ns zdl.ddc
  (:require
   [clojure.string :as str]
   [jsonista.core :as json]
   [taoensso.timbre :as log]
   [zdl.metadata :as metadata]
   [zdl.schema :as schema]
   [zdl.util :refer [assoc*]])
  (:import
   (java.io DataInputStream OutputStream)
   (java.net Socket)
   (java.nio ByteBuffer ByteOrder)
   (java.nio.charset Charset)))

(def ^Charset charset
  (Charset/forName "UTF-8"))

(defn write-str
  [^OutputStream out ^String s]
  (let [payload (. s (getBytes charset))
        len     (count payload)]
    (. out (write (.. (ByteBuffer/allocate 4)
                      (order ByteOrder/LITTLE_ENDIAN)
                      (putInt (unchecked-int len))
                      (array))))
    (. out (write payload))
    (. out (flush))))

(defn read-str
  [^DataInputStream in]
  (let [len (byte-array 4)]
    (.readFully in len)
    (let [len (.. (ByteBuffer/wrap len)
                  (order ByteOrder/LITTLE_ENDIAN)
                  (getInt))
          s   (byte-array len)]
      (.readFully in s)
      (String. s charset))))

(defn request
  [[host port :as endpoint] cmd]
  (try
    (log/debugf "? [%15s %5d/tcp] '%s'" host port cmd)
    (with-open [socket (Socket. ^String host (int port))
                output (.getOutputStream socket)
                input  (DataInputStream. (.getInputStream socket))]
      (write-str output cmd)
      (let [result (read-str input)]
        (log/debugf ". [%15s %5d/tcp %,15d/chars] '%s'"
                    host port (count result) cmd)
        (json/read-value result)))
    (catch Throwable t
      (throw (ex-info "DDC request error" {:endpoint endpoint :cmd cmd} t)))))

(defn parse-metadata
  [m]
  (-> nil
      ;; base
      (assoc* :collection (-> m (get "collection") (metadata/parse-v)))
      (assoc* :url (-> m  (get "url") (metadata/parse-v)))
      (assoc* :file (-> m  (get "basename") (metadata/parse-v)))
      (assoc* :bibl (-> m (get "bibl") (metadata/parse-v)))
      (assoc* :page (or (some-> m (get "page_") (metadata/parse-page))
                        (some-> m (get "pageRange") (metadata/parse-v))))
      ;; classification
      (assoc* :text-class (some->> (-> m (get "textClass") (metadata/parse-vs))
                                   (into [])))
      (assoc* :flags (some->> (-> m (get "flags") (metadata/parse-vs))
                              (into (sorted-set))))
      ;; rights
      (assoc* :access (-> m (get "access") (metadata/parse-v)))
      (assoc* :availability (-> m (get "avail") (metadata/parse-v)))
      ;; time
      (assoc* :date (-> m  (get "date_") (metadata/parse-dates) (first)))
      (assoc* :timestamp (-> m (get "timestamp") (metadata/parse-timestamp)))
      ;; location
      (assoc* :country (-> m (get "country") (metadata/parse-v)))
      (assoc* :region (-> m (get "region") (metadata/parse-v)))
      (assoc* :subregion (-> m (get "subregion") (metadata/parse-v)))))

(defn parse-token
  [n [{:keys [hit?] form "w"} {space-before? "ws" :as _next-token}]]
  (cond-> {:n            n
           :form         form
           :space-after? (= "1" space-before?)}
    (pos? hit?) (assoc :hit? true)))

(defn parse-hit
  [{[_ tokens _] "ctx_" {ks "indices_" :as metadata} "meta_"}]
  (let [ks     (cons :hit? ks)
        tokens (map #(zipmap ks %) tokens)
        tokens (into [] (map-indexed parse-token) (partition-all 2 1 tokens))
        text   (schema/sentence->text {:tokens tokens})]
    {:text      text
     :sentences [{:text text :tokens tokens}]
     :metadata  (parse-metadata metadata)}))

(defn query
  [endpoint q & {:keys [offset page-size timeout]
                 :or   {offset 0 page-size 1000 timeout 30}}]
  (assert (not-empty q) "No query (q) given")
  (let [cmd      (->> (str/join " " [offset page-size timeout])
                      (vector "run_query Distributed" q "json")
                      (str/join \))
        response (request endpoint cmd)
        total    (get response "nhits_" 0)
        results  (get response "hits_")]
    (when (seq results)
      (lazy-cat
       (let [meta {:endpoint endpoint :total total}]
         (map-indexed
          (fn [n hit]
            (with-meta
              (parse-hit hit)
              (assoc meta :offset (+ offset n))))
          results))
       (let [offset (+ offset (count results))]
         (when (< offset total)
           (query endpoint q
                  :offset offset
                  :page-size page-size
                  :timeout timeout)))))))

(comment
  (request ["data.dwds.de" 52170] "info")
  (take 1 (query ["tuvok.bbaw.de" 60260] "Pudel" :page-size 1))
  (take 2 (query ["data.dwds.de" 52170] "Hochkaräter" :page-size 2))
  (do
    (require '[zdl.nlp.annotate :refer [annotate]]
             '[zdl.nlp.vis :as vis])
    (as-> "Hochkaräter #desc_by_date #separate" $
      (query ["data.dwds.de" 52170] $)
      (take 100 $)
      (annotate $)
      (filter (comp (partial < 0.5) :gdex first :sentences) $)
      (sort-by (juxt (comp :gdex first :sentences)
                     (comp str :date :metadata))
               #(compare %2 %1)
               $)
      (map (juxt (comp :gdex first :sentences)
                 (comp :text first :sentences)
                 (comp :bibl :metadata))
           $)
      #_(rand-nth $)
      #_(vis/show! $))))
