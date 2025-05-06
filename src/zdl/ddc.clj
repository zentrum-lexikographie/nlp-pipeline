(ns zdl.ddc
  (:require
   [clojure.string :as str]
   [taoensso.timbre :as log]
   [zdl.metadata :as md]
   [zdl.schema :as schema]
   [zdl.util :refer [assoc*]]
   [charred.api :as charred])
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
        (charred/read-json result)))
    (catch Throwable t
      (throw (ex-info "DDC request error" {:endpoint endpoint :cmd cmd} t)))))

(defn parse-metadata
  [query-metadata m]
  (let [collection (-> m (get "collection") (md/parse-v))
        page       (or (some-> m (get "page_") (md/parse-page))
                       (some-> m (get "pageRange") (md/parse-v)))
        date       (-> m  (get "date_") (md/parse-one-date))
        ts         (-> m (get "timestamp") (md/parse-timestamp))]
    (-> query-metadata
        ;; base
        (assoc* :collection collection)
        (assoc* :url (-> m  (get "url") (md/parse-v)))
        (assoc* :file (-> m  (get "basename") (md/parse-v)))
        (assoc* :bibl (some->> (get m "bibl") (md/parse-bibl page)))
        (assoc* :page page)
        ;; bibl
        (assoc* :author (or (some-> m (get "author") (md/parse-v))
                            (some-> m (get "sentPers") (md/parse-v))))
        (assoc* :editor (or (some-> m (get "editor") (md/parse-v))
                            (condp = collection
                              "wikipedia"  "Wikipedia"
                              "wikivoyage" "Wikivoyage"
                              nil)))
        (assoc* :title  (some-> m (get "title") (md/parse-v)))
        (assoc* :short-title (when (= "gesetze" collection)
                               (some-> m (get "biblSig") (md/parse-v))))
        ;; classification
        (assoc* :text-classes (some->> (-> m (get "textClass") (md/parse-vs))
                                       (into (sorted-set))))
        (assoc* :flags (some->> (-> m (get "flags") (md/parse-vs))
                                (into (sorted-set))))
        ;; rights
        (assoc* :access (-> m (get "access") (md/parse-v)))
        (assoc* :availability (-> m (get "avail") (md/parse-v)))
        ;; time
        (assoc* :date date)
        (assoc* :timestamp ts)
        (assoc* :access-date (or (some-> m (get "urlDate") (md/parse-one-date))
                                 (some-> m (get "dump") (md/parse-one-date))
                                 (some-> ts (md/parse-timestamp-date))))
        (assoc* :first-published (when-let [fp (some-> m (get "firstDate")
                                                       (md/parse-v))]
                                   (let [year (some-> date (subs 0 4))]
                                     (when (not= fp year) fp))))
        ;; location
        (assoc* :country (-> m (get "country") (md/parse-v)))
        (assoc* :region (-> m (get "region") (md/parse-v)))
        (assoc* :subregion (-> m (get "subregion") (md/parse-v))))))

(defn parse-token
  [n [{:keys [hit?] form "w"} {space-before? "ws" :as _next-token}]]
  (cond-> {:n            n
           :form         form
           :space-after? (= "1" space-before?)}
    (pos? hit?) (assoc :hit? true)))

(defn assoc-token-offsets
  [tokens {:keys [form] :as token}]
  (let [len    (count form)
        offset (if-let [{:keys [end space-after?] :as _prev} (last tokens)]
                 (cond-> end space-after? inc) 0)]
    (conj tokens (assoc token
                        :start offset
                        :end (+ offset len)))))

(defn hit->doc
  [query-metadata {[_ tokens _] "ctx_" {ks "indices_" :as metadata} "meta_"}]
  (let [ks       (cons :hit? ks)
        tokens   (map #(zipmap ks %) tokens)
        tokens   (->> (partition-all 2 1 tokens)
                      (map-indexed parse-token)
                      (reduce assoc-token-offsets []))
        text     (schema/tokens->text tokens)
        sentence {:tokens tokens :text text :start 0 :end (count text)}]
    (assoc (parse-metadata query-metadata metadata)
           :chunks [{:sentences [sentence]
                     :text      text}]
           :text text)))

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
       (let [query-metadata {::endpoint endpoint ::total total}]
         (map-indexed
          (fn [n hit] (hit->doc (assoc query-metadata ::offset (+ offset n)) hit))
          results))
       (let [offset (+ offset (count results))]
         (when (< offset total)
           (query endpoint q
                  :offset offset
                  :page-size page-size
                  :timeout timeout)))))))

(comment
  (request ["data.dwds.de" 52170] "info")
  (->> (query ["tuvok.bbaw.de" 60260] "Pudel" :page-size 100)
       (take 100)
       (every? schema/valid-doc?)
       (time))
  (take 2 (query ["data.dwds.de" 52170] "Hochkaräter #CNTXT 1" :page-size 2)))
