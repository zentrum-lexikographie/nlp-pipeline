(ns build
  (:require
   [clojure.data.csv :as csv]
   [clojure.java.io :as io]
   [clojure.tools.build.api :as b])
  (:import
   (javax.xml.namespace QName)
   (org.kohsuke.rngom.ast.util CheckingSchemaBuilder)
   (org.kohsuke.rngom.digested DElementPattern DEmptyPattern DPattern DPatternWalker DRefPattern DSchemaBuilderImpl DXmlTokenPattern)
   (org.kohsuke.rngom.nc NameClass NameClassWalker)
   (org.kohsuke.rngom.parse.xml SAXParseable)
   (org.xml.sax InputSource)
   (org.xml.sax.helpers DefaultHandler)))

(def xml-token-pattern?
  (partial instance? DXmlTokenPattern))

(def element-pattern?
  (partial instance? DElementPattern))

(def empty-pattern?
  (partial instance? DEmptyPattern))

(defn traverse
  "Traverse the schema graph, starting at a given pattern, following
  references and returning a vector of traversed patterns.

  With a given predicate `descend?`, traversal stops at XML tokens not
  fulfilling the predicate."
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

(defn traverse-children
  "Traverses the schema, starting at a given pattern and curtailing the graph by
  stopping traversal on descendant XML tokens.

  This way, only child entities (elements, attributes, data, text and value
  pattern) are returned."
  [^DPattern start]
  (traverse start (some-fn #{start} (complement xml-token-pattern?))))

(defn walk-names
  [^NameClass name-class]
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
     (.accept name-class))
    (into #{} (persistent! patterns))))

(defn parse-names
  "Parses name class of a given XML Token, returning the set of referenced
  qualified names.

  (Does not handle exception classes.)"
  [^DXmlTokenPattern token-pattern]
  (walk-names (.getName token-pattern)))


(defn parse-schema
  [^InputSource src]
  (let [eh             (proxy [DefaultHandler] [] (error [e] (throw e)))
        schema-builder (CheckingSchemaBuilder. (DSchemaBuilderImpl.) eh)]
    (.. (SAXParseable. src eh) (parse schema-builder))))


(defn container-element?
  "Patterns, which only have XML tokens as child patterns, describe container
  elements, i. e. elements without text content (apart from whitespace)."
  [^DElementPattern pattern]
  (->> (traverse-children pattern)
       (remove xml-token-pattern?)
       (remove empty-pattern?)
       (empty?)))

(defn local-names
  [pattern]
  (map #(.getLocalPart ^QName %) (parse-names pattern)))

(def schema-url
  "https://zentrum-lexikographie.github.io/corpus-schema/zdl.rng")

(def analysis-edn-file
  (io/file "src" "zdl" "nlp" "zdl-corpus.edn"))

(def analysis-csv-file
  (io/file "zdl" "corpus-schema.csv"))

(defn corpus-schema-analysis
  [& _]
  (let [schema     (parse-schema (InputSource. schema-url))
        patterns   (traverse schema)
        elements   (filter element-pattern? patterns)
        containers (mapcat local-names (filter container-element? elements))
        content    (mapcat local-names (remove container-element? elements))]
    (spit analysis-edn-file (pr-str {:containers containers
                                     :content    content}))
    (with-open [w (io/writer analysis-csv-file)]
      (->> (concat (map #(vector % "container") containers)
                   (map #(vector % "content") content))
           (sort)
           (csv/write-csv w)))))

(defn top-lemma-jar
  [& _]
  (let [classes "classes"
        basis   (b/create-basis {:project "deps.edn" :aliases #{:sqlite}})
        main    'zdl.nlp.top-lemma]
    (b/delete {:path classes})
    (b/copy-dir {:src-dirs   ["src"]
                 :target-dir classes})
    (b/compile-clj {:basis      basis
                    :ns-compile [main]
                    :class-dir  classes
                    :src-dirs   ["src"]})
    (b/uber {:class-dir classes
             :basis     basis
             :uber-file "top-lemma.jar"
             :main      main})
    (b/delete {:path classes})))

(defn compile-java
  [_]
  (b/javac
   {:src-dirs   ["java"]
    :class-dir  "classes"
    :basis      (b/create-basis {:project "deps.edn"})}))
