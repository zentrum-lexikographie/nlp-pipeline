(ns zdl.nlp.langid
  (:import
   (com.carrotsearch.labs.langid LangIdV3 Model)))

(def model
  (Model/defaultModel))

(def classifiers
  (proxy [ThreadLocal] [] (initialValue [] (LangIdV3. model))))

(defn classifier ^LangIdV3
  []
  (.get ^ThreadLocal classifiers))

(defn classify
  [^String s]
  (.. (classifier) (classify s true) (getLangCode)))

(defn detect-lang
  [{:keys [text] :as chunk}]
  (assoc chunk :lang (classify text)))
