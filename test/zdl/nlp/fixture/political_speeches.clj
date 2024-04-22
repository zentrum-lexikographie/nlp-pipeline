(ns zdl.nlp.fixture.political-speeches
  (:require
   [clojure.java.io :as io]
   [gremid.xml :as gxml])
  (:import
   (java.io InputStream)
   (java.util.zip ZipInputStream)))

(def source-url
  "https://politische-reden.eu/German-Political-Speeches-Corpus.zip")

(defn next-source-file-entry
  [^ZipInputStream input]
  (loop [entry (.getNextEntry input)]
    (when entry
      (if (or (.isDirectory entry) (not (.. entry (getName) (endsWith ".xml"))))
        (recur (.getNextEntry input))
        entry))))

(defn pr->tei-header
  [{{:keys [person titel untertitel _datum _ort _url]} :attrs}]
  [:teiHeader
   [:fileDesc
    [:titleStmt
     (when person
       [:title {:type "speaker"} person])
     (when titel
       [:title {:type "main"} titel])
     (when untertitel
       [:title {:type "sub"} untertitel])]
    [:publicationStmt [:p]]
    [:sourceDesc [:p]]]])

(defn pr->tei
  [node]
  (if (string? node)
    node
    (let [content (map pr->tei (:content node))]
      (condp = (:tag node)
        :<<         (first (filter vector? content))
        :collection [:teiCorpus {:xmlns "http://www.tei-c.org/ns/1.0"} content]
        :text       [:TEI (pr->tei-header node) [:text content]]
        :rohtext    [:body [:ab content]]
        content))))

(defn read-document
  [^InputStream input]
  (-> input gxml/read-events gxml/events->node pr->tei gxml/sexp->node))

(defn documents
  ([]
   (let [input (-> source-url io/input-stream ZipInputStream.)]
     (documents input (next-source-file-entry input))))
  ([^InputStream input entry]
   (when entry
     (lazy-seq
      (cons
       (read-document input)
       (documents input (next-source-file-entry input)))))))

(defn texts
  []
  (let [texts (mapcat (partial gxml/elements :text) (documents))]
    (into [] (map gxml/text) texts)))

(comment
  (rand-nth (take 100 (texts))))
