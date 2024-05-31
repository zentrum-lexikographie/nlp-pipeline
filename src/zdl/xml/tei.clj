(ns zdl.xml.tei
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [gremid.xml :as gxml]
   [zdl.log]
   [zdl.nlp :as nlp]
   [zdl.xml :as xml]
   [zdl.nlp.gdex :as gdex])
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

(defn parse-chunk
  [n events]
  (let [start (first events)
        end   (last events)]
    (loop [events*    events
           milestones []
           text       (StringBuilder.)]
      (if-let [^XMLEvent event (first events*)]
        (let [events*    (rest events*)
              ln         (local-name event)
              milestones (cond-> milestones
                           (and (start-element? event) (ms-tag? ln))
                           (conj {::type  :milestone
                                  ::n     (.length text)
                                  ::start event}))
              text       (cond-> text
                           (.isCharacters event)
                           (.append (-> (.getData ^Characters event)
                                        (str/replace #"\s" " "))))
              text       (cond-> text
                           (and (start-element? event)
                                (not= start event) (para-tag? ln)
                                (not (str/ends-with? text " ")))
                           (.append " "))]
          (recur events* milestones text))
        (let [text (str text)]
          (if (seq (str/trim text))
            (list
             {::type       :chunk
              ::n          n
              ::milestones milestones
              ::start      start
              ::end        end
              :text        text})
            (for [evt events] {::type :event ::n n ::event evt})))))))

(defn chunk?
  [{t ::type}]
  (= :chunk t))

(defn parse-events
  ([events]
   (parse-events events false nil 0 0))
  ([events text? chunk depth n]
   (when-let [^XMLEvent evt (first events)]
     (let [events (rest events)]
       (if text?
         (if chunk
           (let [depth (cond-> depth
                         (start-element? evt) inc
                         (end-element? evt)   dec)
                 end?  (zero? depth)
                 chunk (conj chunk evt)]
             (lazy-cat
              (when end? (parse-chunk n chunk))
              (parse-events events text?
                              (when-not end? chunk) depth (cond-> n end? inc))))
           (if (chunk-start? evt)
             (parse-events events text? [evt] (inc depth) n)
             (lazy-cat
              (list {::type :event ::n n ::event evt})
              (parse-events events (not (text-end? evt)) chunk depth (inc n)))))
         (lazy-cat
          (list {::type :event ::n n ::event evt})
          (parse-events events (text-start? evt) chunk depth (inc n))))))))

(defn merge-annotated-chunks
  [chunks events]
  (if-not (or (empty? chunks) (empty? events))
    (let [chunk (first chunks)
          event (first events)]
      (if (< (chunk ::n) (event ::n))
        (lazy-seq (cons chunk (merge-annotated-chunks (rest chunks) events)))
        (lazy-seq (cons event (merge-annotated-chunks chunks (rest events))))))
    (concat chunks events)))

(defn annotate-events
  [events]
  (merge-annotated-chunks
   (nlp/process-chunks (filter chunk? events))
   (remove chunk? events)))

(defmulti serialize-event ::type)

(defmethod serialize-event :event
  [{::keys [event]}]
  (list event))

(defmethod serialize-event :milestone
  [{::keys [start]}]
  (list start (xml/start->end-element-event start)))

(def space-events
  (list (xml/chars-event " ")))

(defn serialize-token
  [{:keys [start form lemma space-after?] :as _token}]
  [start (concat (list (->> (cond-> {} lemma (assoc :lemma lemma))
                            (xml/start-element-event :w))
                       (xml/chars-event form))
                 (list (xml/end-element-event :w))
                 (when space-after? space-events))])

(defn serialize-span
  [offset {:keys [type label targets]}]
  [offset (list (->> {:type    (name type)
                      :subtype (name label)
                      :targets (str/join " " (map str targets))}
                     (xml/start-element-event :span))
                (xml/end-element-event :span))])

(defn serialize-sentence
  [{:keys [start end tokens spans gdex]}]
  (concat (list [start (list (->>
                              (cond-> {}
                                gdex (assoc :zdl:gdex (gdex/score->str gdex)))
                              (xml/start-element-event :s)))])
          (map serialize-token tokens)
          (map (partial serialize-span end) spans)
          (list [end (list (xml/end-element-event :s))])))

(defn merge-milestone-events
  [events milestones]
  (if-not (or (empty? events) (empty? milestones))
    (let [[offset events*]           (first events)
          {::keys [n] :as milestone} (first milestones)]
      (if (<= n offset)
        (lazy-cat (serialize-event milestone)
                  (merge-milestone-events events (rest milestones)))
        (lazy-cat events*
                  (merge-milestone-events (rest events) milestones))))
    (concat (mapcat second events)
            (mapcat serialize-event milestones))))

(defmethod serialize-event :chunk
  [{::keys [start end milestones] :keys [sentences lang fingerprint]}]
  (let [{:keys [tag attrs]} (xml/start-element-event->node start)
        attrs               (cond-> attrs
                              lang        (assoc :xml:lang lang)
                              fingerprint (assoc :key fingerprint))
        start               (xml/start-element-event tag attrs)]
    (concat (list start)
            (-> (mapcat serialize-sentence sentences)
                (merge-milestone-events milestones))
            (list end))))

(def serialize-events
  (partial mapcat serialize-event))

(defn annotate
  [input output]
  (with-open [input  (io/input-stream input)
              output (io/output-stream output)]
    (->> (gxml/read-events input)
         parse-events annotate-events serialize-events
         (gxml/write-events output))))

(defn -main
  [& _]
  (try
    (zdl.log/configure! true)
    (annotate System/in System/out)
    (finally
      (shutdown-agents))))

(defn events->doc
  [events]
  {:chunks (into [] (filter chunk?) (parse-events events))})

(defn tei-element?
  [event]
  (and (start-element? event) (= "TEI" (local-name event))))

(defn tei-header-element?
  [event]
  (and (start-element? event) (= "teiHeader" (local-name event))))

(def corpus-component-element?
  (some-fn tei-header-element? tei-element?))

(def events->corpus-components
  (partial gxml/events->subtrees identity corpus-component-element?))

(defn events->docs
  [events]
  (let [components (events->corpus-components events)]))
