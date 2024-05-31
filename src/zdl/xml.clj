(ns zdl.xml
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [gremid.xml :as gxml])
  (:import
   (com.ctc.wstx.api WstxOutputProperties)
   (javax.xml.namespace QName)
   (javax.xml.stream XMLInputFactory)
   (javax.xml.stream.events Attribute StartElement XMLEvent)
   (org.codehaus.stax2 XMLInputFactory2 XMLOutputFactory2)))

(def input-factory
  (doto ^XMLInputFactory2 (XMLInputFactory2/newInstance)
    (.configureForRoundTripping)
    (.setProperty XMLInputFactory/IS_NAMESPACE_AWARE false)
    (.setProperty XMLInputFactory/IS_COALESCING true)
    (.setProperty XMLInputFactory/SUPPORT_DTD false)))

(defn read-xml
  [in]
  (binding [gxml/*input-factory* input-factory]
    (with-open [source (io/input-stream in)]
      (gxml/events->node (gxml/read-events source)))))

(def output-factory
  (doto ^XMLOutputFactory2 (XMLOutputFactory2/newInstance)
    (.configureForXmlConformance)
    (.setProperty XMLOutputFactory2/XSP_NAMESPACE_AWARE false)
    (.setProperty WstxOutputProperties/P_USE_DOUBLE_QUOTES_IN_XML_DECL true)))

(def start-document-event
  (.createStartDocument gxml/event-factory "UTF-8"))

(def end-document-event
  (.createEndDocument gxml/event-factory))

(defn start->end-element-event
  [^StartElement start]
  (.createEndElement gxml/event-factory (.getName start) nil))

(defn kw->qname ^QName
  [kw]
  (QName. (name kw)))

(defn qname->kw
  [^QName qn]
  (keyword (.getLocalPart qn)))

(defn attribute-event
  [[k v]]
  (.createAttribute gxml/event-factory ^QName (kw->qname k) ^String v))

(defn attribute->kv
  [^Attribute attr]
  [(qname->kw (.getName attr)) (.getValue attr)])

(defn start-element-event
  ([tag]
   (start-element-event tag nil))
  ([tag attrs]
   (.createStartElement
    gxml/event-factory
    (kw->qname tag)
    (when (seq attrs) (. (into [] (map attribute-event) attrs) (iterator)))
    nil)))

(defn end-element-event
  [tag]
  (.createEndElement gxml/event-factory (kw->qname tag) nil))

(defn chars-event
  [^String s]
  (.createCharacters gxml/event-factory s))

(defn start-element-event->node
  [^StartElement start]
  (let [tag   (qname->kw (.getName start))
        attrs (vec (iterator-seq (.getAttributes start)))]
    (cond-> {:tag tag}
      (seq attrs) (assoc :attrs (into {} (map attribute->kv) attrs)))))

(defn write-node
  [node target]
  (let [events (gxml/node->events node)]
    (when-let [event (first events)]
      (let [inline? (not (.isStartDocument ^XMLEvent event))]
        (gxml/write-events target
                           (concat
                            (when inline? (list start-document-event))
                            (gxml/node->events node)
                            (when inline? (list end-document-event))))))))

(defn write-xml
  [node out]
  (binding [gxml/*output-factory* output-factory]
    (with-open [target (io/output-stream out)]
      (write-node node target))))

(defn xml->str
  [node]
  (binding [gxml/*output-factory* output-factory]
    (with-out-str (write-node node *out*))))

(defn strip-prolog
  [s]
  (str/replace s #"^<\?xml.*?\?>" ""))
