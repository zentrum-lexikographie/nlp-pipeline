;; # TEI-P5/XML Schema Analysis

^{:nextjournal.clerk/toc true}
(ns tei-schema-analysis
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str])
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

{:nextjournal.clerk/visibility {:code :show :result :hide}}

;; ## Parsing the TEI-P5/XML schema for corpora

(def schema-url
  "https://zentrum-lexikographie.github.io/corpus-schema/zdl.rng")

(def schema
  (let [src            (InputSource. schema-url)
        eh             (proxy [DefaultHandler] [] (error [e] (throw e)))
        schema-builder (CheckingSchemaBuilder. (DSchemaBuilderImpl.) eh)]
    (.. (SAXParseable. src eh) (parse schema-builder))))

;; Traverse the schema graph, starting at a given pattern, following
;; references and returning a vector of traversed patterns. With a
;; given predicate `descend?`, traversal stops at XML tokens not
;; fulfilling the predicate.

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

^{:nextjournal.clerk/visibility {:result :show}}
(def patterns
  (traverse schema))

;; ## Extracting element definitions and names

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


^{:nextjournal.clerk/visibility {:result :show}}
(def elements
  (into [] (filter #(instance? DElementPattern %)) patterns))

;; ## Extracting element classes
;;

(defn element-by-name
  [k]
  (some #(when ((local-names %) k) %) elements))

(defn model-elements
  [k]
  (let [define (first (filter #(= k (.getName %)) schema))]
    (into []
          (filter #(instance? DElementPattern %))
          (traverse-children (.getPattern define)))))

;; ### Extracting container elements
;;
;; Patterns, which only have XML tokens as child patterns, describe
;; container elements, i. e. elements without text content (apart from
;; whitespace).

(defn container-element?
  [^DElementPattern pattern]
  (->> (traverse-children pattern)
       (remove #(instance? DXmlTokenPattern %))
       (remove #(instance? DEmptyPattern %))
       (empty?)))

(def containers
  (into #{} (filter container-element?) elements))

;; ### Extracting milestone elements

(defn empty-element?
  [^DElementPattern pattern]
  (->> (traverse-children pattern)
       (remove #{pattern})
       (remove #(instance? DAttributePattern %))
       (remove #(instance? DEmptyPattern %))
       (empty?)))

(def milestones
  (filter empty-element? (model-elements "model.milestoneLike")))

;; ### Extracting chunk-level elements

(def chunks
  (->>
   (concat (model-elements "model.divTop")
           (model-elements "model.divBottom")
           (model-elements "model.common"))
   (remove (into #{} (concat (model-elements "model.oddDecl")
                             (model-elements "model.egLike"))))))

;; ### Extracting paragraph-like elements

(def paras
  (concat (model-elements "model.lLike")
          (model-elements "model.pLike")
          (list (element-by-name "headItem")
                (element-by-name "headLabel")
                (element-by-name "item")
                (element-by-name "label")
                (element-by-name "row"))))

;; ## Write element classes to files
(defn write-element-names->files!
  []
  (let [dir (doto (io/file "src" "zdl" "xml" "tei") (.mkdirs))]
    (doseq [[k els] (zipmap ["containers" "milestones" "chunks" "paras"]
                            [containers milestones chunks paras])]
      (spit (io/file dir (format "%s.txt" k))
            (str/join \newline (into (sorted-set) (mapcat local-names els)))))))

#_(write-element-names->files!)
