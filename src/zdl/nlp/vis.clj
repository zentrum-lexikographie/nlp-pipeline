(ns zdl.nlp.vis
  (:require
   [clojure.java.browse :refer [browse-url]]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [dali]
   [dali.io]
   [dali.layout :as layout]
   [dali.layout.utils :refer [bounds->anchor-point]]
   [dali.prefab :as prefab]
   [dali.syntax :as d]
   [garden.core :refer [css]]
   [net.cgrand.enlive-html :as en]
   [retrograde :as retro]
   [zdl.nlp.deps :as deps])
  (:import
   (java.io File)))

;; ## Styling

(def dwds-blue
  "#337ab7")

(def tagger-orange
  :darkorange)

(def styles
  (css [[:text {:font-family "Source Sans Pro"}]
        [:text.token {:font-weight :bold}]
        [:text.oov {:fill :maroon}]
        [:text.iv  {:fill :forestgreen}]
        [:text.dep :text.tags :text.entity  {:fill tagger-orange}]
        [:text.lemma :text.collocation {:fill dwds-blue}]
        [:polyline.entity :polyline.collocation {:stroke-width 2 :fill :none}]
        [:polyline.entity {:stroke tagger-orange}]
        [:polyline.collocation {:stroke dwds-blue}]
        [:.dep-box {:fill :none}]
        [:.dep-rel :.dep-assoc {:stroke :dimgray}]]))

;; ## SVG helpers/components

(require 'dali.layout.matrix)
(require 'dali.layout.place)
(require 'dali.layout.surround)

(defn el-id
  ([s]
   (keyword (str s)))
  ([s n]
   (keyword (format "%s%03d" (str s) n))))

(defn select-el
  [& args]
  [(keyword (str "#" (name (apply el-id args))))])

(defn el-by-selector
  [doc selector]
  (first (en/select doc selector)))

(defmethod layout/layout-nodes ::edge
  [doc {:keys [attrs]} _elements bounds-fn]
  (let [from        (el-by-selector doc (attrs :from))
        to          (el-by-selector doc (attrs :to))
        coords      (attrs :coords)
        [from to]   (coords (bounds-fn from) (bounds-fn to))
        attrs       (attrs :attrs)]
    [(d/dali->ixml [:polyline attrs from to])]))

(dali/register-layout-tag ::edge)

(defmethod layout/layout-nodes ::stack
  [_ _ nodes bounds-fn]
  (let [[_ y]       (bounds->anchor-point :top-left (bounds-fn (first nodes)))
        get-size    (fn get-size [[_ _ [_ h]]] h)
        get-pos     (fn get-pos [[_ [_ y] _]] y)
        initial-pos y]
    #_:clj-kondo/ignore
    (retro/transform
     [this-gap 0           16
      bounds   nil         (bounds-fn element)
      size     0           (get-size bounds)
      pos      0           (get-pos bounds)
      this-pos initial-pos (+ this-pos' size' this-gap')
      element              (d/add-transform element [:translate [0 this-pos]])]
     nodes)))

(dali/register-layout-tag ::stack)

(defn text
  [s attrs & {:keys [title?] :or {title? true}}]
  [:text (merge {:font-size 16} attrs) s
   (when title? [:title s])])

;; ## Dependency tree

(defn dep-node
  [{:keys [n deprel]}]
  [:g
   (text deprel {:id (el-id "dep-" n) :class :dep})
   [:dali/surround
    {:select (select-el "dep-" n) :padding 32 :attrs {:class :dep-box}}]])

(defn dep-tree-levels
  ([deps root]
   (dep-tree-levels deps 0 root))
  ([deps l n]
   (cons [n l] (mapcat (partial dep-tree-levels deps (inc l)) (deps n)))))

(defn dep-tree-nodes
  [{:keys [tokens deps]}]
  (let [dep-tree-levels    (into {} (dep-tree-levels deps (:n (deps/root tokens))))
        max-dep-tree-level (reduce max (vals dep-tree-levels))]
    (for [level (range (inc max-dep-tree-level))
          token tokens]
      (if (= level (dep-tree-levels (:n token))) (dep-node token) :_))))

(defn dep-tree-edge-coords
  [bounds-from bounds-to]
  (let [[x1 y1] (bounds->anchor-point :top bounds-from)
        [x2 y2] (bounds->anchor-point :bottom bounds-to)]
    [[x1 (- y1 4)] [x2 (+ y2 4)]]))

(defn dep-tree-edges
  [{:keys [tokens]}]
  (concat
   (for [{:keys [n head]} tokens :when head]
     [::edge {:from   (select-el "dep-" n)
              :to     (select-el "dep-" head)
              :coords dep-tree-edge-coords
              :attrs  {:class        :dep-rel
                       :dali/z-index -1}}])
   (for [{:keys [n]} tokens]
     [::edge {:from   (select-el "tags-" n)
              :to     (select-el "dep-" n)
              :coords dep-tree-edge-coords
              :attrs  {:class        :dep-assoc
                       :dali/z-index -1}}])))

;; ## Named entities

(defn range-disjunct?
  [cmp {as :start ae :end} {bs :start be :end}]
  (or (cmp ae bs) (cmp be as)))

(defn assign-range-level
  [cmp levels v]
  (let [l (first (filter #(every? (partial range-disjunct? cmp v) (levels %))
                         (range (count levels))))]
    (if l (assoc levels l (conj (levels l) v)) (conj levels [v]))))

(defn entity-range-coords
  [y [_ [x1 _] [_ _]] [_ [x2 _] [w2 _]]]
  [[x1 y] [(+ x2 w2) y]])

(defn entity->ranges
  [{:keys [targets] :as entity}]
  (let [targets (sort targets)]
    (assoc entity :start (first targets) :end (last targets))))

(defn entities
  [{:keys [entities]}]
  (let [levels (->> entities
                    (map-indexed #(assoc (entity->ranges %2) :n %1))
                    (sort-by (juxt #(- (:end %) (:start %)) :start))
                    (reduce (partial assign-range-level <) []))]
    (for [[li level] (map-indexed list levels)
          entity     level]
      (let [{:keys [n]} entity]
        [:g {}
         [::edge {:from   (select-el "lemma-" (:start entity))
                  :to     (select-el "lemma-" (:end   entity))
                  :coords (partial entity-range-coords (* 48 li))
                  :attrs  {:id                (el-id "entity-" n)
                           :class             :entity
                           :dali/z-index      -1
                           :dali/marker-start {:id :dm :fill tagger-orange}
                           :dali/marker-end   {:id :dm :fill tagger-orange}}}]
         [:dali/place {:relative-to [(el-id "entity-" n) :bottom]
                       :anchor      :top
                       :offset      [0 8]}
          [:text {:class :entity :font-size 16} (:label entity)]]]))))

;; ## Collocations

(defn collocation->relations
  [{:keys [targets] :as collocation}]
  (for [[start end] (partition 2 1 (sort targets))]
    (assoc collocation :start start :end end)))

(defn collocation-range-coords
  [y [_ [x1 _] [w1 _]] [_ [x2 _] [w2 _]]]
  [[(+ x1 (/ w1 2)) y] [(+ x2 (/ w2 2)) y]])

(defn collocations
  [{:keys [collocations]}]
  (let [levels (->> collocations
                    (map-indexed #(assoc %2 :n %1))
                    (mapcat collocation->relations)
                    (map-indexed #(assoc %2 :i %1))
                    (sort-by (juxt #(- (:end %) (:start %)) :start))
                    (reduce (partial assign-range-level <=) []))]
    (for [[li level]  (map-indexed list levels)
          collocation level]
      (let [{:keys [i n]} collocation]
        [:g {}
         [::edge {:from   (select-el "lemma-" (:start collocation))
                  :to     (select-el "lemma-" (:end   collocation))
                  :coords (partial collocation-range-coords (* li 48))
                  :attrs  {:id                (el-id "collocation-" i)
                           :class             :collocation
                           :dali/marker-start {:id :dm :fill dwds-blue}
                           :dali/marker-end   {:id :dm :fill dwds-blue}}}]
         [:dali/place {:relative-to [(el-id "collocation-" i) :bottom]
                       :anchor      :top
                       :offset      [0 8]}
          [:text {:class     :collocation
                  :font-size 16}
           (format "%s (%d)" (:label collocation) n)]]]))))

;; ## Tokens and tagging

(def token->tags
  (juxt :upos :xpos :number :gender :case :person :tense))

(defn tagging
  [{:keys [tokens]}]
  (concat
   (for [[ti t] (map-indexed #(vector %1 (token->tags %2)) tokens)]
     [:text {:id (el-id "tags-" ti) :class :tags :font-size 16}
      (or (not-empty (str/join ", " (remove nil? t))) "–")])
   (for [{:keys [oov? text n]} tokens]
     [:text {:id (keyword (str "t" n))
             :class (str "token " (if oov? "oov" "iv"))
             :font-size 24}
      text])
   (for [{:keys [lemma n] dwdsmor-lemma :zdl.nlp.dwdsmor/lemma} tokens]
     [:text {:id (el-id "lemma-" n) :class :lemma :font-size 24}
      (or dwdsmor-lemma lemma "–")])))

;; ## Annotation document

(defn document
  [{:keys [tokens] :as sentence}]
  [:dali/page
   [:defs
    (prefab/dot-marker :dm :radius 16)
    (d/css styles)]
   [::stack
    [:dali/matrix {:column-gap 32
                   :row-gap    16
                   :columns    (count tokens)
                   :position   [32 0]}
     (dep-tree-nodes sentence)
     (tagging sentence)]
    (some->> (entities sentence) seq (into [:g {}]))
    (some->> (collocations sentence) seq (into [:g {}]))]
   (dep-tree-edges sentence)])

(defn svg-file
  [sentence f]
  (dali.io/render-svg (document sentence) (io/file f)))

(defn svg-str
  [sentence]
  (dali.io/render-svg-string (document sentence)))

(defn png-file
  [sentence f]
  (dali.io/render-png (document sentence) (io/file f)))

(defn show!
  [sentence]
  (let [temp-svg (File/createTempFile "zdl-nlp-vis-" ".svg")]
    (.deleteOnExit temp-svg)
    (svg-file sentence temp-svg)
    (browse-url (.toURL temp-svg))))
