(ns zdl.ddc.corpora
  (:require
   [clojure.string :as str]
   [hato.client :as hc]
   [jsonista.core :as json]
   [zdl.ddc :as ddc]
   [zdl.env :as env]
   [zdl.util :refer [slurp-edn spit-edn]]
   [taoensso.timbre :as log]))

(defn parse-list
  [corpora]
  (into
   (sorted-map)
   (map (fn [{:keys [host port corpus]}] [corpus [host (parse-long port)]]))
   corpora))

(def list-req
  (merge {:method :get
          :url    "https://kaskade.dwds.de/dstar/?f=json"}
         (when env/dstar-corpora-credentials
           (merge {:url "https://kaskade.dwds.de/dstar/intern.perl?f=json"}
                  env/dstar-corpora-credentials))))

(defn request-list
  []
  (-> (hc/request list-req)
      :body (json/read-value json/keyword-keys-object-mapper) parse-list))

(defonce endpoints
  (delay (request-list)))

(defn get-corpus-name
  [c]
  (get c "name"))

(defn get-corpora
  [c]
  (get c "corpora"))

(defn server-names
  [info]
  (let [subcorpora (tree-seq get-corpus-name get-corpora info)
        names      (map get-corpus-name subcorpora)
        servers    (filter #(str/starts-with? % "server:") names)]
    (into #{} servers)))

(defn meta-corpus?
  [info]
  (< 1 (count (server-names info))))

(defn corpus-info
  [[id endpoint]]
  (let [info (ddc/request endpoint "info")]
    (assoc info :id id :meta-corpus? (meta-corpus? info))))

(defonce infos
  (delay
    (->> (pmap corpus-info @endpoints)
         (into (sorted-map) (map (juxt :id identity))))))

(defonce collections
  (->> (for [[id info] @infos :when (not (info :meta-corpus?))] id)
       (into (sorted-set) )
       (delay)))

(defn assoc-corpus
  [corpus v]
  (vary-meta v assoc  :corpus corpus))

(def ^:dynamic *num-results-per-corpus*
  10000)

(defn query*
  [corpus & args]
  (let [results (->> (concat args (list :page-size *num-results-per-corpus*))
                     (apply ddc/query (@endpoints corpus))
                     (into [] (comp (take *num-results-per-corpus*)
                                    (map (partial assoc-corpus corpus)))))]
    (log/debugf "? [%15s] [%,9d] %s" corpus (count results) args)
    results))

(defn query
  [corpora & args]
  (assert (every? @endpoints corpora) (str "Could not resolve " corpora))
  (flatten (pmap #(apply query* % args) corpora)))

(comment
  @collections
  (binding [*num-results-per-corpus* 100]
    (spit-edn "sample.edn" (take 10 (query #{"kernbasis"} "Pudel"))))

  (slurp-edn "sample.edn")
  (for [[id info] @infos :when (info :meta-corpus?)] id))
