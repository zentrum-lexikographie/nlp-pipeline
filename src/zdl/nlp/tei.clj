(ns zdl.nlp.tei
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [gremid.xml :as gxml])
  (:import
   (javax.xml.stream XMLEventFactory  XMLStreamConstants)
   (javax.xml.stream.events Characters StartElement XMLEvent)
   (org.codehaus.stax2.evt XMLEventFactory2)))

(def schema-categories
  (read-string (slurp (io/resource "zdl/nlp/zdl-corpus.edn"))))

(def container-event?
  (comp some? (into #{} (:containers schema-categories))))

(def content-event?
  (comp some? (into #{} (:content schema-categories))))

(def whitespace-ms-event?
  (comp some? #{"cb" "lb" "pb"}))

(def text-event?
  (comp (partial = "text")))

(def text-unit-event?
  (comp some? #{"ab" "head" "p"}))

(def sentence-event?
  (partial = "s"))

(defn event->local-name
  [^StartElement event]
  (.. event (getName) (getLocalPart)))

(def ^XMLEventFactory event-factory
  (XMLEventFactory2/newInstance))

(defn normalize-space
  ([events]
   (normalize-space events []))
  ([events stack]
   (when-let [^XMLEvent event (first events)]
     (lazy-seq
      (let [et (.getEventType event)]
        (condp = et
          XMLStreamConstants/CHARACTERS
          (let [txt   (.getData ^Characters event)
                txt*  (condp = (peek stack)
                        :container (str/replace txt #"\s+" "")
                        :content   (str/replace txt #"\s+" " ")
                        :other     txt)
                event (if-not (= txt* txt)
                        (if (.isCData ^Characters event)
                          (.createCData event-factory txt*)
                          (.createCharacters event-factory  txt*))
                        event)]
            (cons event (normalize-space (rest events) stack)))
          XMLStreamConstants/START_DOCUMENT
          (normalize-space (rest events) (conj stack :container))
          XMLStreamConstants/START_ELEMENT
          (->> (let [ln (event->local-name event)]
                 (cond
                   (content-event? ln)      :content
                   (container-event? ln)    :container
                   (.isStartDocument event) :document
                   :else                    :other))
               (conj stack)
               (normalize-space (rest events)))
          XMLStreamConstants/END_ELEMENT
          (normalize-space (rest events) (pop stack))
          XMLStreamConstants/END_DOCUMENT
          (normalize-space (rest events) (pop stack))))))))

(def whitespace-event
  (.createCharacters event-factory " "))

(defn splice-whitespace
  [^XMLEvent event]
  (cond->> (list event)
    (and (.isStartElement event)
         (whitespace-ms-event? (event->local-name event)))
    (cons whitespace-event)))

(defn text-segment?
  [^XMLEvent event]
  (and (.isStartElement event)
       (#{"ab" "head" "p"} (event->local-name event))))

(defn milestone-event?
  [^XMLEvent event]
  (and (.isStartElement event)
       (let [ln (event->local-name event)]
         (or (whitespace-ms-event? ln)
             (some? (#{"anchor" "milestone"} ln))))))

(defn build-segment
  [events]
  (let [start (first events)]
    (loop [events     events
           text       (StringBuilder.)
           milestones []]
      (if-let [^XMLEvent event (first events)]
        (cond
          (milestone-event? event) (recur (rest events)
                                          (->> {:offset (.length text)
                                                :event  event}
                                               (conj milestones))
                                          text)
          (.isCharacters event)    (recur (rest events)
                                          milestones
                                          (->> (.getData ^Characters event)
                                               (.append text)))
          :else                    (recur (rest events)
                                          milestones
                                          text))
        {:start      start
         :text       (str text)
         :milestones milestones}))))

(defn segment
  [events]
  (gxml/events->subtrees build-segment text-segment? events))

(comment
  (gxml/read-events (io/file "test")))
