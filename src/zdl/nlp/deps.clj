(ns zdl.nlp.deps)

;; ## Dependency trees

(defn assoc-deps*
  [m {:keys [n head]}]
  (update m head (fnil conj []) n))

(defn assoc-deps
  [{:keys [tokens] :as sentence}]
  (->> (reduce assoc-deps* {} (filter :head tokens))
       (assoc sentence :deps)))

(defn root
  [tokens]
  (first (filter (comp nil? :head) tokens)))

(defn paths
  ([{:keys [tokens] :as sentence}]
   (paths sentence nil (:n (root tokens))))
  ([{:keys [deps tokens] :as sentence} path n]
   (let [path (cons (tokens n) path)]
     (cons path (mapcat (partial paths sentence path) (deps n))))))

;; ## Wordprofile relations (Collocations)

(defrecord ColloPattern [k pattern matches?])

(defn collo-pattern*
  [tagsets]
  (into []
        (map (fn [[pos deprel]] (cond-> {:pos? pos} deprel (assoc :deprel? deprel))))
        (partition 2 2 nil tagsets)))

(defn collo-pattern
  ([k pattern]
   (collo-pattern k pattern (constantly true)))
  ([k pattern matches?]
   (->ColloPattern k (collo-pattern* pattern) matches?)))

;; TODO: add filter constraints!

(defn case?
  [{:keys [deprel]}]
  (= "CASE" deprel))

(defn cop?
  [{:keys [deprel]}]
  (= "COP" deprel))

(defn aux?
  [{:keys [upos]}]
  (= "AUX" upos))

(defn gdet?
  [{:keys [form]}]
  (#{"des" "der" "eines" "einer"} form))

(def cop-aux?
  (every-pred cop? aux?))

(defn deps-tokens
  [{:keys [deps tokens]} {:keys [n]}]
  (seq (map tokens (deps n))))

(defn subject-pred?
  [sentence [_c1 c2]]
  (let [deps  (deps-tokens sentence c2)]
    (and (some cop-aux? deps) (every? (complement case?) deps))))

(defn object-pred?
  [sentence [c1 _c2]]
  (let [deps (deps-tokens sentence c1)]
    (some (fn [{:keys [form]}] (#{"als" "für"} form)) deps)))

(defn gmod?
  [sentence [c1 _c2]]
  (let [deps (deps-tokens sentence c1)]
    (and (every? (complement case?) deps) (some gdet? deps))))

(defn subja?
  [sentence [_c1 c2]]
  (let [deps (deps-tokens sentence c2)]
    (every? (complement cop?) deps)))

(def collo-patterns
  [(collo-pattern "GMOD"  [#{"NOUN"}              #{"NMOD"}         #{"NOUN"}]                                                     gmod?)           
   (collo-pattern "OBJ"   [#{"NOUN"}              #{"OBJ" "IOBJ"}   #{"VERB"}])
   (collo-pattern "PRED"  [#{"NOUN"}              #{"NSUBJ"}        #{"NOUN" "VERB" "ADJ"}]                                        subject-pred?)
   (collo-pattern "PRED"  [#{"NOUN" "VERB" "ADJ"} #{"OBJ" "OBL"}    #{"VERB"}]                                                     object-pred?)
   (collo-pattern "SUBJA" [#{"NOUN"}              #{"NSUBJ"}        #{"NOUN" "VERB" "ADJ"}]                                        subja?)
   (collo-pattern "SUBJP" [#{"NOUN"}              #{"NSUBJ:PASS"}   #{"VERB"}])
   (collo-pattern "ADV"   [#{"ADJ" "ADV"}         #{"ADVMOD"}       #{"VERB" "ADJ"}])
   (collo-pattern "ATTR"  [#{"ADJ"}               #{"AMOD"}         #{"NOUN"}])
   (collo-pattern "PP"    [#{"ADP"}               #{"CASE"}         #{"NOUN"}               #{"NMOD"}       #{"NOUN"}])
   (collo-pattern "PP"    [#{"ADP"}               #{"CASE"}         #{"NOUN" "ADJ" "ADV"}   #{"OBL"}        #{"VERB"}])
   (collo-pattern "VZ"    [#{"ADP"}               #{"COMPOUND:PRT"} #{"VERB" "AUX" "ADJ"}])
   (collo-pattern "KON"   [#{"CCONJ"}             #{"CC"}           #{"NOUN" "VERB" "ADJ"}  #{"CONJ"}       #{"NOUN" "VERB" "ADJ"}])
   (collo-pattern "KOM"   [#{"CCONJ"}             #{"CASE"}         #{"NOUN"}               #{"OBL" "NMOD"} #{"ADJ" "VERB" "NOUN"}])])

(defn collo-pattern-matches-token?
  [{:keys [pos? deprel?]} {:keys [upos deprel]}]
  (and upos (pos? upos) (or (nil? deprel?) (and deprel (deprel? deprel)))))

(defn collocations*
  [{:keys [k pattern matches?]} sentence path]
  (let [pattern-len (count pattern)
        path        (take pattern-len path)]
    (when (and (= pattern-len (count path))
               (every? identity (map collo-pattern-matches-token? pattern path))
               (matches? sentence path))
      (list {:type    :collocation
             :label   k
             :targets (into [] (map :n path))}))))

(defn collocations
  [sentence]
  (for [cp collo-patterns p (paths sentence) c (collocations* cp sentence p)] c))

(defn analyze-collocations
  [{:keys [sentences] :as chunk}]
  (->>
   (for [{:keys [deps] :as sentence} sentences]
     (let [sentence (cond-> sentence (nil? deps) (assoc-deps))
           collocs  (collocations sentence)]
       (cond-> sentence (seq collocs) (update :spans (fnil into []) collocs))))
   (vec) (assoc chunk :sentences)))

(defn extract-collocations
  [{:keys [sentences]}]
  (for [sentence sentences
        token    (sentence :tokens)
        :when    (token :hit?)
        :let     [n  (token :n)
                  n? (partial = n)]
        span     (sentence :spans)
        :let     [targets (span :targets)]
        :when    (and (= :collocation (span :type)) (some n? targets))]
    {
     :label      (str (when-not (= n (first targets)) "~") (span :label))
     :token      token
     :collocates (into []
                       (comp (remove n?) (map (sentence :tokens)))
                       (span :targets))
     :sentence   sentence}))
