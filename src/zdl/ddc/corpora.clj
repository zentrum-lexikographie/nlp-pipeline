(ns zdl.ddc.corpora
  (:require
   [clojure.string :as str]
   [hato.client :as hc]
   [jsonista.core :as json]
   [taoensso.timbre :as log]
   [zdl.ddc :as ddc]
   [zdl.env :as env]
   [zdl.nlp]
   [zdl.nlp.gdex :as gdex]
   [zdl.nlp.hash :as hash]
   [zdl.util :refer [slurp-edn spit-edn]]
   [zdl.nlp.deps :as deps]))

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

(def ^:dynamic *queried*
  #{"kernbasis" "dtaxl"})

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
  [& args]
  (assert (every? @endpoints *queried*) (str "Could not resolve " *queried*))
  (flatten (pmap #(apply query* % args) *queried*)))

#_(defn good-examples
  [q]
  (->> (query q) (zdl.nlp/annotate) (hash/deduplicate)
       (filter (comp gdex/good? gdex/get-score))
       (sort-by gdex/get-score #(compare %2 %1))
       (vec)))

#_(defn balanced-good-examples-by
  [kf vs]
  (->> (group-by kf vs) (vals)
       (mapcat (partial map-indexed #(assoc %2 :rank %1)))
       (sort-by (juxt :rank (comp - gdex/get-score)))
       (vec)))

(comment
  @collections

  (binding [*queried* #{"kernbasis"}
            *num-results-per-corpus* 10000]
    (spit-edn "sample.edn" (good-examples "Sinn")))

  (->>
   (for [collocation (mapcat #_deps/extract-collocations (slurp-edn "sample.edn"))]
     (-> collocation :collocates last :lemma))
   (frequencies)
   (sort-by second #(compare %2 %1)))
  (for [[id info] @infos :when (info :meta-corpus?)] id))
