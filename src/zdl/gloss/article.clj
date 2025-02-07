(ns zdl.gloss.article
  (:require
   [clojure.string :as str]
   [gremid.xml :as gxml]
   [zdl.util :refer [form->id]]
   [zdl.xml])
  (:import
   (java.time LocalDate)
   (java.time.format DateTimeFormatter)))

(defn doc->urn
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

(defn doc->xml
  [{[{:keys [sentences]}] :chunks :as doc}]
  [:Beleg {:class "invisible"}
   [:Belegtext
    (for [s sentences t (s :tokens) xml (token->xml-list t)] xml)]
   [:Fundstelle {:Fundort (doc->urn doc)}
    (if-let [url (doc :url)]
      (list
       (when-let [author (doc :author)]
         [:Autor author])
       (when-let [editor (doc :editor)]
         [:Herausgeber editor])
       [:Titel (doc :title)]
       (when-let [short-title (doc :short-title)]
         [:Kurztitel short-title])
       [:Datum (some-> (doc :date) (format-date))]
       (when-let [first-published (doc :first-published)]
         [:Erstpublikation first-published])
       [:URL url]
       (when-let [access-date (doc :access-date)]
         [:Aufrufdatum (format-date access-date)]))
      (list (doc :bibl)))]])

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
       (map doc->xml examples)]]]]))
