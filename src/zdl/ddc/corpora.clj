(ns zdl.ddc.corpora
  (:require
   [charred.api :as charred]
   [clojure.string :as str]
   [hato.client :as hc]
   [taoensso.timbre :as log]
   [zdl.ddc :as ddc]
   [zdl.env :as env]
   [zdl.nlp :as nlp]
   [zdl.nlp.gdex :as gdex]))

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
      :body (charred/read-json :key-fn keyword) parse-list))

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

(defn query*
  [corpus max-results & args]
  (let [results (->> (concat args (list :page-size max-results))
                     (apply ddc/query (@endpoints corpus))
                     (into [] (comp (take max-results)
                                    (map #(assoc % ::corpus corpus)))))]
    (log/debugf "? [%15s] [%,9d] %s" corpus (count results) args)
    results))

(defn query
  [corpora max-results-per-corpus & args]
  (assert (every? @endpoints corpora) (str "Could not resolve " corpora))
  (flatten (pmap #(apply query* % max-results-per-corpus args) corpora)))

(defn deduplicate
  ([docs]
   (deduplicate docs #{}))
  ([docs seen]
   (when-let [{[{[{:keys [fingerprint]}] :sentences}] :chunks :as doc}
              (first docs)]
     (lazy-seq
      (if (seen fingerprint)
        (deduplicate (rest docs) seen)
        (cons doc (deduplicate (rest docs) (conj seen fingerprint))))))))

(defn gdex-score
  [{[{[{:keys [gdex]}] :sentences}] :chunks :as _doc}]
  gdex)

(def gdex-good?
  (comp gdex/good? gdex-score))

(defn good-examples
  [corpora max-results-per-corpus q]
  (->> (query corpora max-results-per-corpus q)
       (nlp/annotate-docs) (deduplicate)
       (filter gdex-good?) (sort-by gdex-score #(compare %2 %1)) (vec)))

(defn balanced-good-examples-by
  [kf vs]
  (->> (group-by kf vs) (vals)
       (mapcat (partial map-indexed #(assoc %2 :rank %1)))
       (sort-by (juxt :rank (comp - gdex-score)))
       (vec)))

(comment
  @collections

  (binding [*queried*                #{"kernbasis"}
            *num-results-per-corpus* 100]
    (good-examples "Sinn"))

  (for [[id info] @infos :when (info :meta-corpus?)] id))
