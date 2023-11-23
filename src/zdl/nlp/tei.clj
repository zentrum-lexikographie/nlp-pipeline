(ns zdl.nlp.tei
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [gremid.xml :as gxml])
  (:import
   (javax.xml.stream XMLEventFactory XMLStreamConstants)
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

(defn corpus-segment?
  [^XMLEvent event]
  (and (.isStartElement event)
       (#{"teiHeader" "TEI"} (event->local-name event))))

(defn folia-class-prop
  [k v]
  (when v [k {:class v}]))

(defn folia-feat-prop
  [k v]
  (when v [:feat {:subset (name k) :class v}]))

(defn folia-id
  [prefix k n]
  (str prefix "." k "." n))

(defn folia-sentence
  ([node]
   (folia-sentence "s" node))
  ([id node]
   (let [word-id (partial folia-id id "w")
         tokens  (gxml/elements :w node)
         entities (->> (gxml/elements :span node)
                       (filter #(= "entity" (gxml/attr :type %))))]
     [:s {:xml:id id}
      (folia-class-prop :lang (get-in node [:attrs :xml:lang]))
      (for [{:keys [attrs] :as token} tokens]
        (let [text (gxml/text-content token)]
          [:w (cond-> {:xml:id (word-id (some-> attrs :n parse-long))}
                (not (str/ends-with? text " ")) (assoc :space "no"))
           [:t (str/trim text)]
           (folia-class-prop :lemma (attrs :lemma))
           (into
            (folia-class-prop :pos (attrs :pos))
            (list
             (folia-feat-prop :num    (some-> attrs :number))
             (folia-feat-prop :gender (some-> attrs :gender ))
             (folia-feat-prop :case   (some-> attrs :case))
             (folia-feat-prop :person (some-> attrs :person))
             (folia-feat-prop :tense  (some-> attrs :tense))))]))
      (when (some (partial gxml/attr :dep) tokens)
        [:dependencies
         (for [{{:keys [n head dep]} :attrs} tokens :when head]
           [:dependency {:class dep}
            [:hd  [:wref {:id (word-id (parse-long head))}]]
            [:dep [:wref {:id (word-id (parse-long n))}]]])])
      (when (seq entities)
        [:entities
         (for [{{:keys [subtype from to]} :attrs} entities]
           [:entity {:class subtype}
            (for [n (range (parse-long from) (inc (parse-long to)))]
              [:wref {:id (word-id n)}])])])
      #_(when collocations
        [:collocations
         (for [{:keys [k path]} collocations]
           [:collocation {:class (tag->str k)}
            (for [n path]
              [:wref {:id (word-id n)}])])])])))

(defn folia
  ([node]
   (first (folia false false node)))
  ([div? chunk? {:keys [tag content] :as node}]
   (if (= :s tag)
     (list (folia-sentence node))
     (let [chunk-tag? (some? (#{:ab :head :p} tag))
           chunk??    (or chunk? chunk-tag?)
           div??      (or div? (= :div tag))
           content    (mapcat #(folia div?? chunk?? %) content)]
       (when (seq content)
         (cond
           (= :TEI tag) (list [:FoLiA {:xmlns "http://ilk.uvt.nl/folia"}
                               [:text content]])
           (not div?)   (if (= :div tag) (list [:div content]) content)
           (not chunk?) (if chunk-tag? (list [:p content]) content)
           :else        content))))))

(comment
  (->> (io/file "kafka.annotated.tei.xml")
       #_(io/file "/home/gregor/kern.corpus.xml")
       (gxml/read-events)
       (gxml/events->subtrees corpus-segment?)
       (filter (comp (partial = :TEI) :tag))
       (map folia)
       (take 1)))
