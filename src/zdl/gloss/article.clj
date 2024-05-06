(ns zdl.gloss.article
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [gremid.xml :as gxml]
   [zdl.util :refer [form->id]]
   [zdl.xml])
  (:import
   (java.time LocalDate)
   (java.time.format DateTimeFormatter)))

(defn metadata->urn
  [{:keys [collection file page]}]
  (->> (cond-> ["dwds" collection file] page (conj page))
       (str/join \:)))

(defn token->xml-list
  [{:keys [hit? form space-after?]}]
  (cond-> (list (if hit? [:Stichwort form] form)) space-after? (concat " ")))

(def short-date-formatter
  (DateTimeFormatter/ofPattern "dd.MM.yyyy"))

(defn format-date
  [v]
  (some-> v LocalDate/parse (.format short-date-formatter)))

(defn chunk->xml
  [{:keys [metadata sentences]}]
  [:Beleg {:class "invisible"}
   [:Belegtext
    (for [s sentences t (s :tokens) xml (token->xml-list t)] xml)]
   [:Fundstelle {:Fundort (metadata->urn metadata)}
    (if-let [url (metadata :url)]
      (list
       (when-let [author (metadata :author)]
         [:Autor author])
       (when-let [editor (metadata :editor)]
         [:Herausgeber editor])
       [:Titel (metadata :title)]
       (when-let [short-title (metadata :short-title)]
         [:Kurztitel short-title])
       [:Datum (some-> (metadata :date) (format-date))]
       (when-let [first-published (metadata :first-published)]
         [:Erstpublikation first-published])
       [:URL url]
       (when-let [access-date (metadata :access-date)]
         [:Aufrufdatum (format-date access-date)]))
      (list (metadata :bibl)))]])

(defn gloss->xml
  [form gloss examples]
  (gxml/sexp->node
   [:DWDS {:xmlns "http://www.dwds.de/ns/1.0"}
    [:Artikel {:Quelle           "DWDS"
               :Status           "Artikelrumpf"
               :Typ              "Vollartikel"
               :xml:id           (str "E_" (form->id form))
               :Zeitstempel      (str (LocalDate/now))
               :Erstellungsdatum (str (LocalDate/now))
               :Erstfassung      "ZDL"}
     [:Formangabe {:Typ "Hauptform"}
      [:Schreibung form]
      [:Grammatik [:Wortklasse]]
      [:Diasystematik]]
     [:Orthografie]
     [:Verweise]
     [:Diachronie
      [:Etymologie]
      [:Formgeschichte]
      [:Bedeutungsgeschichte]
      [:Verweise]]
     [:Lesart
      [:Syntagmatik]
      [:Diasystematik]
      [:Verweise]
      [:Definition {:Typ "Basis"}]
      [:Kommentar gloss]
      [:Kollokationen]
      [:Verwendungsbeispiele
       (map chunk->xml examples)]]]]))

(comment
  (->
   
   (zdl.xml/write-xml (io/file "sample.xml"))))
