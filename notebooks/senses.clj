^{:nextjournal.clerk/visibility {:code :hide} :nextjournal.clerk/toc false}
(ns senses
  (:require
   [gremid.xml :as gx]
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [nextjournal.clerk :as clerk]))

;; # Lesarten im ZDL-Artikelbestand
;;
;; _Gregor Middell (05.06.2024)_
;;

(clerk/vl
 {:$schema  "https://vega.github.io/schema/vega-lite/v5.json"
  :data     {:values (->>
                      (fs/glob "../lex/test-data/prod" "**/*.xml")
                      (pmap (fn [f] (with-open [input (io/input-stream (fs/file f))]
                                      (-> input gx/read-events gx/events->node))))
                      (mapcat (partial gx/elements :Artikel))
                      (filter (fn [{{status :Status article-type :Typ} :attrs}]
                                (and (= "Red-f" status)
                                     (= "Vollartikel" article-type))))
                      (map (fn [article] (count (gx/elements :Lesart article))))
                      (frequencies)
                      (map (partial zipmap [:senses :articles]))
                      (remove (fn [{:keys [articles]}] (<= articles 10))))}
  :encoding {:x {:field "senses"
                 :type  "nominal"
                 :title "Lesarten"}
             :y {:field "articles"
                 :type  "quantitative"
                 :title "Artikel"
                 :scale {:base 10 :type "log"}}}
  :width    600
  :mark     "bar"})
