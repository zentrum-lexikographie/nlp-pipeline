(ns zdl.nlp.fixture.political-speeches
  (:require
   [clojure.java.io :as io]
   [gremid.xml :as gxml]
   [zdl.xml.tei :as tei]
   [babashka.fs :as fs])
  (:import
   (java.io InputStream)
   (java.util.zip ZipInputStream)))

(def source-url
  "https://politische-reden.eu/German-Political-Speeches-Corpus.zip")

(def source-file
  (fs/file "test-data" "German-Political-Speeches-Corpus.zip"))

(defn source
  []
  (when-not (fs/exists? source-file)
    (fs/create-dirs (fs/parent source-file))
    (with-open [input (io/input-stream source-url)
                output (io/output-stream source-file)]
      (io/copy input output)))
  source-file)

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

(defn tei-element?
  [event]
  (and (tei/start-element? event) (= "TEI" (tei/local-name event))))

(defn read-zip-entry
  [zip-entry input]
  (let [corpus (-> input gxml/read-events gxml/events->node pr->tei
                   gxml/sexp->node gxml/node->events)
        texts (->> (gxml/events->subtrees vec tei-element? corpus)
                   (filter vector?))]
    (for [text texts]
      (-> (tei/events->doc text)
          (assoc :collection "politische_reden"
                 :file (.getName zip-entry))))))

(defn docs
  ([]
   (let [input (-> (source) io/input-stream ZipInputStream.)]
     (docs input (next-source-file-entry input))))
  ([^InputStream input entry]
   (when entry
     (lazy-cat
      (read-zip-entry entry input)
      (docs input (next-source-file-entry input))))))
