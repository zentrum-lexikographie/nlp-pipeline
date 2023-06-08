(ns zdl.nlp.annotation)

;; ## Merge DWDSmor analysis

(def number-hdt->smor
  {:sing #{:sg}
   :plur #{:pl}})

(def pos-hdt->smor
  {:adja          #{:adj :card :indef :ord}
   :adjd          #{:adj}
   :appo          #{:postp}
   :appr          #{:prep}
   :apprart       #{:prepart}
   :apzr          #{:postp :prep}
   :itj           #{:intj}
   :kokom         #{:conj}
   :kon           #{:conj}
   :koui          #{:conj}
   :kous          #{:conj}
   :ne            #{:nn :nprop}
   :nn            #{:nn :nprop}
   :pdat          #{:dem}
   :pds           #{:dem}
   :piat          #{:indef}
   :pidat         #{:indef}
   :pis           #{:indef}
   :pper          #{:ppro}
   :pposat        #{:poss}
   :pposs         #{:poss}
   :prelat        #{:rel}
   :prels         #{:rel}
   :prf           #{:ppro}
   :prop          #{:adv :proadv}
   :ptka          #{:ptcl}
   :ptkant        #{:intj :ptcl}
   :ptkneg        #{:ptcl}
   :ptkvz         #{:adv :prep :vpart}
   :ptkzu         #{:ptcl}
   :pwat          #{:wpro}
   :pwav          #{:adv}
   :pws           #{:wpro}
   :vafin         #{:v}
   :vaimp         #{:v}
   :vainf         #{:v}
   :vapp          #{:v}
   :vmfin         #{:v}
   :vminf         #{:v}
   :vmpp          #{:v}
   :vvfin         #{:v}
   :vvimp         #{:v}
   :vvinf         #{:v}
   :vvizu         #{:v}
   :vvpp          #{:v}
   (keyword "$(") #{:punct}
   (keyword "$,") #{:punct}
   (keyword "$.") #{:punct}})

(defn rank-analysis
  [pos? num? gender? case? person? tense? analysis]
  (let [{:keys [pos number gender case person tense]} analysis]
    (->>
     (cond-> 0
       (pos? pos)       (+ 3)
       (num? number)    (+ 2)
       (gender? gender) (+ 1)
       (case? case)     (+ 1)
       (person? person) (+ 1)
       (tense? tense)   (+ 1))
     (assoc analysis :rank))))

(def false*
  (constantly false))

(defn reverse-compare
  [a b]
  (compare b a))

(defn merge-feature
  [tagger-feature dwdsmor-feature]
  (or dwdsmor-feature tagger-feature))

(defn merge-dwdsmor-analysis
  [{:keys [dwdsmor xpos number gender case person tense] :as token}]
  (if-not dwdsmor
    token
    (let [analyses (as-> (partial
                          rank-analysis
                          (if xpos   (get pos-hdt->smor xpos #{xpos}) false*)
                          (if number (get number-hdt->smor number #{number}) false*)
                          (if gender #{gender} false*)
                          (if case   #{case} false*)
                          (if person #{person} false*)
                          (if tense  #{tense} false*)) $
                     (map $ dwdsmor)
                     (sort-by :rank reverse-compare $)
                     (into [] $))
          analysis (first analyses)]
      (-> token
          (assoc  :dwdsmor analyses)
          (update :lemma merge-feature (:lemma analysis))
          (update :gender merge-feature (:gender analysis))
          (update :tense merge-feature (:tense analysis))))))

(defn assoc-dwdsmor-analysis
  [{:keys [tokens] :as sentence}]
  (assoc sentence :tokens (into [] (map merge-dwdsmor-analysis) tokens)))

;; ## Dependency trees

(defn assoc-deps
  [tokens {:keys [n] :as head}]
  (let [deps (filter (comp (partial = n) :head) tokens)]
    (cond-> head
      (seq deps) (assoc :deps (into [] (map (partial assoc-deps tokens)) deps)))))

(defn tokens->dep-tree
  [tokens]
  (let [root (first (filter (comp nil? :head) tokens))]
    (assoc-deps tokens root)))

(defn assoc-dep-tree
  [{:keys [tokens] :as sentence}]
  (cond-> sentence
    (some :head tokens) (assoc :dep-tree (tokens->dep-tree tokens))))

(defn dep-tree-paths
  ([token]
   (dep-tree-paths nil token))
  ([parent-path token]
   (let [path (cons token parent-path)]
     (cons path (mapcat (partial dep-tree-paths path) (:deps token))))))

(defn assoc-dep-tree-paths
  [{:keys [dep-tree] :as sentence}]
  (cond-> sentence
    dep-tree (assoc :dep-tree-paths (vec (dep-tree-paths dep-tree)))))

;; ## Wordprofile relations (Collocations)

(defrecord Collocation [k pattern matches?])

(defn collo-pattern
  [tagsets]
  (into []
        (map (fn [[pos deprel]] (cond-> {:pos? pos} deprel (assoc :deprel? deprel))))
        (partition 2 2 nil tagsets)))

(defn collocation
  ([k pattern]
   (->Collocation k (collo-pattern pattern) (constantly true)))
  ([k pattern matches?]
   (->Collocation k (collo-pattern pattern) matches?)))


;; TODO: add filter constraints!

(defn token-pred
  [k vs]
  (comp vs k))

(defn text-pred
  [vs]
  (comp vs :text))

(def cop-aux?
  (every-pred (token-pred :deprel #{:cop}) (token-pred :upos #{:aux})))

(def subject-pred?
  (comp
   (every-pred
    (comp (partial some cop-aux?) :deps)
    (comp (partial every? (complement (token-pred :deprel #{:case}))) :deps))
   last))

(def object-pred?
  (comp
   (partial some (token-pred :text #{"als" "für"}))
   :deps
   first))

(def gmod?
  (comp
   (every-pred
    (comp (partial every? (complement (token-pred :deprel #{:case}))) :deps)
    (comp (partial some (token-pred :text #{"des" "der" "eines" "einer"})) :deps))
   first))

(def subja?
  (comp
   (partial every? (complement (token-pred :deprel #{:cop})))
   :deps
   last))

(def collocations
  [(collocation :gmod  [#{:noun}            #{:nmod}         #{:noun}]            gmod?)           
   (collocation :obj   [#{:noun}            #{:obj :iobj}    #{:verb}])
   (collocation :pred  [#{:noun}            #{:nsubj}        #{:noun :verb :adj}] subject-pred?)
   (collocation :pred  [#{:noun :verb :adj} #{:obj :obl}     #{:verb}]            object-pred?)
   (collocation :subja [#{:noun}            #{:nsubj}        #{:noun :verb :adj}] subja?)
   (collocation :subjp [#{:noun}            #{:nsubj:pass}   #{:verb}])
   (collocation :adv   [#{:adj :adv}        #{:advmod}       #{:verb :adj}])
   (collocation :attr  [#{:adj}             #{:amod}         #{:noun}])
   (collocation :pp    [#{:adp}             #{:case}         #{:noun}             #{:nmod}      #{:noun}])
   (collocation :pp    [#{:adp}             #{:case}         #{:noun :adj :adv}   #{:obl}       #{:verb}])
   (collocation :vz    [#{:adp}             #{:compound:prt} #{:verb :aux :adj}])
   (collocation :kon   [#{:cconj}           #{:cc}           #{:verb :aux :adj}])
   (collocation :kom   [#{:cconj}           #{:case}         #{:noun}             #{:obl :nmod} #{:adj :verb :noun}])])

(defn collo-pattern-matches-token?
  [{:keys [pos? deprel?]} {:keys [upos deprel]}]
  (and upos (pos? upos) (or (nil? deprel?) (and deprel (deprel? deprel)))))

(defn extract-collocation
  [{:keys [k pattern matches?]} path]
  (let [pattern-len (count pattern)
        path        (take pattern-len path)]
    (when (and (= pattern-len (count path))
               (every? identity (map collo-pattern-matches-token? pattern path))
               (matches? path))
      (list {:k k :path (into [] (map :n path))}))))

(defn extract-collocations
  [dep-tree-paths]
  (vec (for [c collocations p dep-tree-paths c (extract-collocation c p)] c)))

(defn assoc-collocations
  [{:keys [dep-tree-paths] :as sentence}]
  (cond-> sentence
    dep-tree-paths (assoc :collocations (extract-collocations dep-tree-paths))))

(defn process
  [sentence]
  (-> sentence
      (assoc-dwdsmor-analysis)
      (assoc-dep-tree)
      (assoc-dep-tree-paths)
      (assoc-collocations)))
