^{:nextjournal.clerk/visibility {:code :hide} :nextjournal.clerk/toc true}
(ns simplemma-coverage
  (:require [clojure.java.io :as io]
            [nextjournal.clerk :as clerk]
            [nextjournal.clerk.viewer :as v]))

;; # Simplemma Coverage Report
;;
;; _Gregor Middell (06.06.2024)_
;;

{:nextjournal.clerk/visibility {:code :hide :result :hide}}

(defn number->html
  [v]
  (->
   (cond (float? v) "%.2f"
         (int? v)   "%,d"
         :else      "%s")
   (format v)
   (clerk/html)))

(def custom-table-viewer
  (update v/table-viewer :add-viewers v/add-viewers
          [(assoc v/number-viewer :transform-fn (v/update-val number->html))]))

(def coverage-data
  (read-string (slurp (io/file "data" "simplemma-coverage.edn"))))

(def coverage
  (->>
   (for [[pos cov] (:coverage coverage-data)]
     (let [t  (get cov true 0)
           f  (get cov false 0)
           s  (+ t f)
           tr (/ t s)
           fr (/ f s)]
       [pos s fr tr f t]))
   (sort-by (fn [[pos _s fr _tr f _t]] [(- f) (- fr) pos]))
   (vec)))

(def total
  (reduce (fn [total [_pos s]] (+ total s)) 0 coverage))

(def covered
  (reduce (fn [total [_pos _s _fr _tr _f t]] (+ total t)) 0 coverage))

(def not-covered
  (->>
   (for [[pos stats] (:uncovered coverage-data)
         :when       (not= "NE" pos)
         [form n]     stats
         :when        form]
     [pos form n])
   (reduce (fn [m [pos form n]] (update m (str pos "/" form) (fnil + 0) n)) {})
   (sort-by (comp - second))
   (take 1000)
   (into [])))

(defn per
  [base v]
  (float (* base v)))

(def percent
  (partial per 100))

(def permille
  (partial per 1000))

{:nextjournal.clerk/visibility {:result :show}}

;; ## Dataset

(clerk/with-viewer custom-table-viewer
  {::clerk/page-size nil ::clerk/width :prose}
  [["Number of analyzed giga tokens:" (float (/ total (* 1024 1024 1024)))]
   ["Percentage of covered tokens:" (percent (/ covered total))]])

;; * based on analysis of a subset of Wordprofile 2024 data
;; * includes Kernkorpus, Wikipedia and newspapers (Bild, FAZ, FR, SZ, Welt):

;; ## Coverage by Part-of-Speech

(clerk/with-viewer custom-table-viewer
  {::clerk/page-size nil ::clerk/width :prose}
  (clerk/use-headers
   (cons
    ["POS" "% of total" "Missing" "Miss %" "Covered" "Coverage %" ]
    (for [[pos s fr tr f t] coverage]
      [pos (percent (/ s total)) f (percent fr) t (percent tr)]))))

;; ## Not Covered: Top-1000

(clerk/with-viewer custom-table-viewer
  {::clerk/page-size nil ::clerk/width :prose}
  (clerk/use-headers
   (cons ["Form" "Missing" "‰ of total"]
         (map (fn [[form n]] [form n (permille (/ n total))]) not-covered))))
