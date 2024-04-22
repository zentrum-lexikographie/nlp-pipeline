(ns zdl.xml.tei
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [gremid.xml :as gxml])
  (:import
   (javax.xml.stream XMLStreamConstants)
   (javax.xml.stream.events Characters EndElement StartElement XMLEvent)))

(def ns-uri
  "http://www.tei-c.org/ns/1.0")

(def element-classes
  (into (sorted-map)
        (map (fn [k]
               (with-open [r (io/reader
                              (io/resource
                               (format "zdl/xml/tei/%s.txt" (name k))))]
                 [k (into (sorted-set) (line-seq r))])))
        [:chunks :containers :milestones :paras]))

(defn element-class-pred
  [k]
  (comp some? (element-classes k)))

(def container-tag?
  (element-class-pred :containers))

(def ms-tag?
  (element-class-pred :milestones))

(def chunk-tag?
  (element-class-pred :chunks))

(def para-tag?
  (element-class-pred :paras))

(def text-tag?
  (comp (partial = "text")))

(def text-unit-tag?
  (comp some? #{"ab" "head" "p"}))

(def sentence-tag?
  (partial = "s"))

(defprotocol XMLEventProperties
  (start-element? [this])
  (end-element? [this])
  (local-name [this]))

(extend-protocol XMLEventProperties
  StartElement
  (start-element? [_] true)
  (end-element? [_] false)
  (local-name [this] (.. this (getName) (getLocalPart)))
  EndElement
  (start-element? [_] false)
  (end-element? [_] true)
  (local-name [this] (.. this (getName) (getLocalPart)))
  XMLEvent
  (start-element? [_] false)
  (end-element? [_] false)
  (local-name [_]))

(defn normalize-space
  ([events]
   (normalize-space events []))
  ([events stack]
   (when-let [^XMLEvent event (first events)]
     (lazy-seq
      (let [et (.getEventType event)]
        (condp = et
          XMLStreamConstants/CHARACTERS
          (let [container? (peek stack)
                txt        (.getData ^Characters event)
                txt*       (str/replace txt #"\s+" (if container? "" " "))
                event      (if-not (= txt* txt)
                             (if (.isCData ^Characters event)
                               (.createCData gxml/event-factory txt*)
                               (.createCharacters gxml/event-factory  txt*))
                             event)]
            (cons event (normalize-space (rest events) stack)))

          XMLStreamConstants/START_DOCUMENT
          (cons event (normalize-space (rest events) (conj stack true)))

          XMLStreamConstants/START_ELEMENT
          (let [ln    (local-name event)
                stack (conj stack (container-tag? ln))]
            (cons event (normalize-space (rest events) stack)))

          XMLStreamConstants/END_ELEMENT
          (cons event (normalize-space (rest events) (pop stack)))

          XMLStreamConstants/END_DOCUMENT
          (cons event (normalize-space (rest events) (pop stack)))

          (cons event (normalize-space (rest events) stack))))))))

(defn text-start?
  [^XMLEvent event]
  (and (start-element? event) (= "text" (local-name event))))

(defn text-end?
  [^XMLEvent event]
  (and (end-element? event) (= "text" (local-name event))))

(defn chunk-start?
  [^XMLEvent event]
  (and (start-element? event) (chunk-tag? (local-name event))))

(defn ->chunk
  [events]
  (let [start (first events)
        end   (last events)]
    (loop [events     events
           milestones []
           text       (StringBuilder.)]
      (if-let [^XMLEvent event (first events)]
        (let [events     (rest events)
              ln         (local-name event)
              milestones (cond-> milestones
                           (and (start-element? event) (ms-tag? ln))
                           (conj (let [offset (.length text)]
                                   {:start offset
                                    :end   offset
                                    :event event})))
              text       (cond-> text
                           (.isCharacters event)
                           (.append (-> (.getData ^Characters event)
                                        (str/replace #"\s" " "))))
              text       (cond-> text
                           (and (start-element? event)
                                (not= start event) (para-tag? ln)
                                (not (str/ends-with? text " ")))
                           (.append " "))]
          (recur events milestones text))
        {:text        (str text)
         :start-event start
         :end-event   end
         :milestones  milestones}))))

(defn chunks
  ([events]
   (chunks events false nil 0))
  ([events text? chunk depth]
   (when-let [^XMLEvent evt (first events)]
     (let [events (rest events)]
       (if text?
         (if chunk
           (let [depth (cond-> depth (start-element? evt) inc (end-element? evt) dec)
                 end?  (zero? depth)
                 chunk (conj chunk evt)]
             (lazy-cat
              (when end? (list (->chunk chunk)))
              (chunks events text? (when-not end? chunk) depth)))
           (if (chunk-start? evt)
             (chunks events text? [evt] (inc depth))
             (lazy-cat
              (list evt)
              (chunks events (not (text-end? evt)) chunk depth))))
         (lazy-cat
          (list evt)
          (chunks events (text-start? evt) chunk depth)))))))
