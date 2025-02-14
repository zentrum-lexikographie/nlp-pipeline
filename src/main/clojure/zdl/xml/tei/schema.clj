(ns zdl.xml.tei.schema
  (:require
   [charred.api :as charred])
  (:import
   (javax.xml.namespace QName)
   (org.kohsuke.rngom.ast.util CheckingSchemaBuilder)
   (org.kohsuke.rngom.digested DAttributePattern
                               DElementPattern DEmptyPattern DPattern
                               DPatternWalker DRefPattern DSchemaBuilderImpl
                               DXmlTokenPattern)
   (org.kohsuke.rngom.nc NameClassWalker)
   (org.kohsuke.rngom.parse.xml SAXParseable)
   (org.xml.sax InputSource)
   (org.xml.sax.helpers DefaultHandler)))

(def schema-url
  "https://zentrum-lexikographie.github.io/corpus-schema/zdl.rng")

(def schema
  (let [src            (InputSource. schema-url)
        eh             (proxy [DefaultHandler] [] (error [e] (throw e)))
        schema-builder (CheckingSchemaBuilder. (DSchemaBuilderImpl.) eh)]
    (.. (SAXParseable. src eh) (parse schema-builder))))

(defn traverse
  ([^DPattern start]
   (traverse start (constantly true)))
  ([^DPattern start descend?]
   (let [patterns  (transient [])
         add!      (fn [p] (conj! patterns p) nil)
         seen-refs (transient #{})]
     (->>
      (proxy [DPatternWalker] []
        (onXmlToken [^DXmlTokenPattern p]
          (add! p)
          (when (descend? p)
            (.. p (getChild) (accept this))))
        (onData [p] (add! p))
        (onEmpty [p] (add! p))
        (onText [p] (add! p))
        (onValue [p] (add! p))
        (onNotAllowed [p] (add! p))
        (onRef
          [^DRefPattern rp]
          (let [name (.getName rp)]
            (when-not (seen-refs name)
              (conj! seen-refs name)
              (.. rp (getTarget) (getPattern) (accept this))))))
      (.accept start))
     (persistent! patterns))))

(def patterns
  (traverse schema))

(defn local-names
  "Parses name class of a given XML Token, returning the set of
  referenced local names. (Does not handle exception classes.)"
  [^DXmlTokenPattern token-pattern]
  (let [patterns (transient [])
        add!     (fn [m] (conj! patterns m) nil)]
    (->>
     (proxy [NameClassWalker] []
       (visitName [^QName qn]
         (add! qn))
       (visitAnyName [])
       (visitNsName [ns])
       (visitAnyNameExcept [nc])
       (visitNsNameExcept [ns nc])
       (visitNull []))
     (.accept (.getName token-pattern)))
    (into #{} (map #(.getLocalPart ^QName %)) (persistent! patterns))))

(defn traverse-children
  "Traverses the schema, starting at a given pattern and curtailing the
  graph by stopping traversal on descendant XML tokens. This way, only
  child entities (elements, attributes, data, text and value pattern)
  are returned."
  [^DPattern start]
  (traverse start (some-fn #{start} (complement #(instance? DXmlTokenPattern %)))))


(def elements
  (into [] (filter #(instance? DElementPattern %)) patterns))

(defn element-by-name
  [k]
  (some #(when ((local-names %) k) %) elements))

(defn model-elements
  [k]
  (let [define (first (filter #(= k (.getName %)) schema))]
    (into []
          (filter #(instance? DElementPattern %))
          (traverse-children (.getPattern define)))))

(defn container-element?
  [^DElementPattern pattern]
  (->> (traverse-children pattern)
       (remove #(instance? DXmlTokenPattern %))
       (remove #(instance? DEmptyPattern %))
       (empty?)))

(def containers
  (into #{} (filter container-element?) elements))

(defn empty-element?
  [^DElementPattern pattern]
  (->> (traverse-children pattern)
       (remove #{pattern})
       (remove #(instance? DAttributePattern %))
       (remove #(instance? DEmptyPattern %))
       (empty?)))

(def milestones
  (filter empty-element? (model-elements "model.milestoneLike")))

(def chunks
  (->>
   (concat (model-elements "model.divTop")
           (model-elements "model.divBottom")
           (model-elements "model.common"))
   (remove (into #{} (concat (model-elements "model.oddDecl")
                             (model-elements "model.egLike"))))))

(def paras
  (concat (model-elements "model.lLike")
          (model-elements "model.pLike")
          (list (element-by-name "headItem")
                (element-by-name "headLabel")
                (element-by-name "item")
                (element-by-name "label")
                (element-by-name "row"))))

(defn print-json
  [& _]
  (->> [containers milestones chunks paras]
       (map (fn [els] (into (sorted-set) (mapcat local-names) els)))
       (zipmap ["containers" "milestones" "chunks" "paras"])
       (charred/write-json-str)
       (println)))
