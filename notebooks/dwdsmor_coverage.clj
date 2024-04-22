^{:nextjournal.clerk/visibility {:code :hide} :nextjournal.clerk/toc true}
(ns dwdsmor-coverage
  (:require [clojure.java.io :as io]
            [nextjournal.clerk :as clerk]
            [nextjournal.clerk.viewer :as v]
            [clojure.data.csv :as csv]))

;; # DWDSmor Coverage Report
;;
;; _Gregor Middell (19.03.2024)_
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


(def coverage
  (letfn [(csv->n [s] (parse-long (or (not-empty s) "0")))
          (parse-record [[pos t f]]
            (let [t (csv->n t)
                  f (csv->n f)
                  s (+ t f)
                  tr (/ t s)
                  fr (/ f s)]
              [pos s fr tr f t]))
          (sort-key [[pos _s fr _tr f _t]] [(- f) (- fr) pos])]
    (with-open [in (io/reader (io/file "notebooks" "dwdsmor-coverage.csv"))]
      (->>
       (csv/read-csv in)
       (into [] (map parse-record))
       (sort-by sort-key)))))

(def total
  (reduce (fn [total [_pos s]] (+ total s)) 0 coverage))

(def not-covered
  (with-open [in (io/reader (io/file "notebooks" "dwdsmor-not-covered.csv"))]
    (->>
     (csv/read-csv in)
     (map (fn [[form n]] [form (parse-long n)]))
     (take 1000)
     (into []))))

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
  [["Number of analyzed giga tokens:" (float (/ total (* 1024 1024 1024)))]])

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
