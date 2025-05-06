(ns zdl.korap.koral
  (:import (de.ids_mannheim.korap.query.serialize QuerySerializer)))

(defn parse-query
  ([query]
   (parse-query "poliqarpplus" query))
  ([query-type query]
   (.. (QuerySerializer.) (setQuery query query-type) (build))))

(comment
  (time (parse-query "contains(<s>,[orth=zu][pos=ADJA])"))
  (class (parse-query "[orth=zu][pos=ADJA]")))
