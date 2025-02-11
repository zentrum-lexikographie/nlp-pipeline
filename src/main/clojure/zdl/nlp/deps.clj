(ns zdl.nlp.deps)

;; ## Dependency trees

(defn assoc-deps*
  [m {:keys [n head]}]
  (update m head (fnil conj []) n))

(defn assoc-deps
  [{:keys [tokens deps] :as sentence}]
  (if deps
    sentence
    (->> (reduce assoc-deps* {} (filter :head tokens))
         (assoc sentence :deps))))

(defn root
  [tokens]
  (first (filter (comp nil? :head) tokens)))

(defn paths
  ([{:keys [tokens] :as sentence}]
   (when-let [root (root tokens)]
     (paths sentence nil (root :n))))
  ([{:keys [deps tokens] :as sentence} path n]
   (let [path (cons (tokens n) path)]
     (cons path (mapcat (partial paths sentence path) (deps n))))))

;; ## Collocation extraction
;;
;; https://www.ims.uni-stuttgart.de/documents/ressourcen/korpora/tiger-corpus/annotation/tiger_scheme-syntax.pdf

(defn pp-collocation?
  [{:keys [deprel] :as _head-token}]
  (or (= "MO" deprel) (= "MDR" deprel)))

(defn kon-collocation?
  [{:keys [deprel upos] :as _head-token}]
  (and (= "CD" deprel) (= "CCONJ" upos)))

(defn aux-verb?
  [aux {:keys [head deprel upos]}]
  (and (= aux head) (= "OC" deprel) (= "VERB" upos)))

(defn collocations
  [{:keys [tokens]} {:keys [deprel head n upos case]}]
  (let [head-token (some-> head tokens)
        head-pos   (some-> head-token :upos)]
    (condp = [deprel upos]
      ["MO" "ADV"]  (when (or (= "VERB" head-pos) (= "ADV" head-pos))
                      (list ["ADV" head n]))
      ["NK" "NOUN"] (when (and (= "ADP" head-pos) (pp-collocation? head-token))
                      (list ["PP" (-> head-token :head) head n]))
      ["NK" "ADJ"]  (condp = head-pos
                      "NOUN" (list ["ATTR" head n])
                      "ADP"  (when (pp-collocation? head-token)
                               (list ["PP" (-> head-token :head) head n]))
                      nil)
      ["SB" "NOUN"] (when (= "Nom" case)
                      (condp = head-pos
                        "VERB" (list ["SUBJA" head n])
                        "AUX"  (when-let [verb (->> tokens
                                                    (filter #(aux-verb? head %))
                                                    (map :n)
                                                    (first))]
                                 (list ["SUBJP" verb n]))
                        nil))
      ["OA" "NOUN"] (when (and (= "Acc" case) (= "VERB" head-pos))
                      (list ["OBJ" head n]))
      ["DA" "NOUN"] (when (and (= "Dat" case) (= "VERB" head-pos))
                      (list ["OBJO" head n]))
      ["AG" "NOUN"] (when (and (= "Gen" case) (= "NOUN" head-pos))
                      (list ["GMOD" head n]))
      ["CJ" "ADJ"]  (when (kon-collocation? head-token)
                      (list ["KON" (-> head-token :head) n]))
      ["CJ" "ADV"]  (when (kon-collocation? head-token)
                      (list ["KON" (-> head-token :head) n]))
      ["CJ" "NOUN"] (when (kon-collocation? head-token)
                      (list ["KON" (-> head-token :head) n]))
      ["CC" "NOUN"] (when (or (= "NOUN" head-pos)
                              (= "ADJ" head-pos)
                              (= "ADV" head-pos)
                              (= "VERB" head-pos))
                      (list ["KOM" head n]))
      ["PD" "ADV"]  (when (= "NOUN" head-pos) (list ["PRED" head n]))
      ["PD" "NOUN"] (when (= "NOUN" head-pos) (list ["PRED" head n]))
      nil)))

(defn analyze-collocations
  [{:keys [tokens] :as sentence}]
  (let [collocs (->> (mapcat (partial collocations sentence) tokens)
                     (map (fn [[k & targets]]
                            {:type    :collocation
                             :label   k
                             :targets (vec targets)})))]
    (cond-> sentence (seq collocs) (update :spans (fnil into []) collocs))))

(defn extract-collocations
  [sentence]
  (for [token    (sentence :tokens)
        :when    (token :hit?)
        :let     [n  (token :n)
                  n? (partial = n)]
        span     (sentence :spans)
        :let     [targets (span :targets)]
        :when    (and (= :collocation (span :type)) (some n? targets))]
    {:label      (str (when-not (= n (first targets)) "~") (span :label))
     :token      token
     :collocates (into []
                       (comp (remove n?) (map (sentence :tokens)))
                       (span :targets))
     :sentence   sentence}))
