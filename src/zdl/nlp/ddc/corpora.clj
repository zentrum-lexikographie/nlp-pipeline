(ns zdl.nlp.ddc.corpora
  (:require
   [clojure.core.async :as a]
   [clojure.string :as str]
   [hato.client :as hc]
   [jsonista.core :as json]
   [zdl.nlp.ddc :as ddc]
   [zdl.nlp.env :as env]))

(defn parse-list
  [corpora]
  (into
   (sorted-map)
   (map (fn [{:keys [host port corpus]}] [corpus [host (parse-long port)]]))
   corpora))

(def ddc-list-req
  (merge {:method :get
          :url    "https://kaskade.dwds.de/dstar/?f=json"}
         (when env/dstar-corpora-credentials
           (merge {:url "https://kaskade.dwds.de/dstar/intern.perl?f=json"}
                  env/dstar-corpora-credentials))))
(defn ddc-list
  []
  (-> (hc/request ddc-list-req)
      :body (json/read-value json/keyword-keys-object-mapper) parse-list))

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
  [{info ::ddc/result}]
  (< 1 (count (server-names info))))

(defn corpus-info
  [[id [host port]]]
  (-> (ddc/request! host port "info")
      (a/pipe (a/chan 1 (map #(assoc % ::id id
                                     ::meta-corpus? (meta-corpus? %)))))))

(defn query-infos!
  []
  (let [infos (a/<!! (a/into [] (a/merge (map corpus-info (ddc-list)))))]
    (when-let [errors (seq (filter ::ddc/error infos))]
      (throw (ex-info "Error(s) while querying DDC endpoints for infos"
                      {::errors  errors
                       ::results infos})))
    (into (sorted-map) (map (juxt ::id identity)) infos)))

(comment
  (for [[id info] (query-infos!)] [id (info ::meta-corpus?)]))
