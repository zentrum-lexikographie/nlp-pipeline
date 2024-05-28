(ns zdl.nlp.dwdsmor
  (:require [clojure.set :refer [intersection]]
            [zdl.env :as env]))

(def transducer
  (delay
    (when env/dwdsmor-lemmatizer-automaton
      (hfst.Transducer/read env/dwdsmor-lemmatizer-automaton))))

(defn lookup
  [^String s]
  (try
    (. ^hfst.Transducer @transducer (lookup s))
    (catch Throwable _)))

(defn parse-analysis
  [analysis]
  (let [lemma (StringBuilder.)
        tags  (java.util.HashSet.)
        pos   (StringBuilder.)]
    (doseq [^String comp analysis]
      (if (. comp (startsWith "<"))
        (let [comp-len (. comp (length))]
          (if (= \+ (. comp (charAt 1)))
            (. pos  (append (. comp (substring 2 (- comp-len 1)))))
            (. tags (add    (. comp (substring 1 (- comp-len 1)))))))
        (. lemma (append comp))))
    {:lemma (. lemma (toString))
     :pos   (. pos (toString))
     :tags  (into #{} tags)}))

(def hdt->smor
  {"ADJA"    #{"ADJ" "CARD" "INDEF" "ORD"}
   "ADJD"    #{"ADJ"}
   "APPO"    #{"POSTP"}
   "APPR"    #{"PREP"}
   "APPRART" #{"PREPART"}
   "APZR"    #{"POSTP" "PREP"}
   "CMP"     #{"Comp"}
   "ITJ"     #{"INTJ"}
   "KOKOM"   #{"CONJ"}
   "KON"     #{"CONJ"}
   "KOUI"    #{"CONJ"}
   "KOUS"    #{"CONJ"}
   "NE"      #{"NN" "NPROP"}
   "NN"      #{"NN" "NPROP"}
   "PDAT"    #{"DEM"}
   "PDS"     #{"DEM"}
   "PIAT"    #{"INDEF"}
   "PIDAT"   #{"INDEF"}
   "PIS"     #{"INDEF"}
   "PPER"    #{"PPRO"}
   "PPOSAT"  #{"POSS"}
   "PPOSS"   #{"POSS"}
   "PRELAT"  #{"REL"}
   "PRELS"   #{"REL"}
   "PRF"     #{"PPRO"}
   "PROP"    #{"ADV" "PROADV"}
   "PTKA"    #{"PTCL"}
   "PTKANT"  #{"INTJ" "PTCL"}
   "PTKNEG"  #{"PTCL"}
   "PTKVZ"   #{"ADV" "PREP" "VPART"}
   "PTKZU"   #{"PTCL"}
   "PWAT"    #{"WPRO"}
   "PWAV"    #{"ADV"}
   "PWS"     #{"WPRO"}
   "SING"    #{"Sg"}
   "PLUR"    #{"Pl"}
   "VAFIN"   #{"V"}
   "VAIMP"   #{"V"}
   "VAINF"   #{"V"}
   "VAPP"    #{"V"}
   "VMFIN"   #{"V"}
   "VMINF"   #{"V"}
   "VMPP"    #{"V"}
   "VVFIN"   #{"V"}
   "VVIMP"   #{"V"}
   "VVINF"   #{"V"}
   "VVIZU"   #{"V"}
   "VVPP"    #{"V"}})

(defn fingerprint
  [{:keys [xpos gender case tense person mood degree]}]
  (->> [xpos gender case tense person mood degree]
       (into #{} (mapcat #(get hdt->smor % (list %))))))

(defn rank
  [fingerprint {:keys [pos tags]}]
  (+ (cond-> 0 (fingerprint pos) (+ 3)) (count (intersection fingerprint tags))))

(defn analyze
  [fingerprint s]
  (->> (lookup s)
       (map parse-analysis)
       (sort-by (partial rank fingerprint) >)
       (first)))

(defn lemmatize
  [{:keys [sentences] :as chunk}]
  (if @transducer
    (->>
     (for [{:keys [tokens] :as sentence} sentences]
       (->>
        (for [t tokens :let [a (analyze (fingerprint t) (t :form))]]
          (cond-> t a (assoc :lemma (:lemma a))))
        (vec) (assoc sentence :tokens)))
     (vec) (assoc chunk :sentences))
    chunk))
