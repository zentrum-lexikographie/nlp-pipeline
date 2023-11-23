(ns zdl.nlp.env
  (:import
   (io.github.cdimascio.dotenv Dotenv)))

(def ^Dotenv dot-env
  (.. Dotenv (configure) (ignoreIfMissing) (load)))

(defn get-env
  ([k]
   (get-env k nil))
  ([^String k df]
   (let [k (str "ZDL_NLP_" k)]
     (or (System/getenv k) (.get dot-env k) df))))

(def environment
  (get-env "ENVIRONMENT" "dev"))

(def dev?
  (= "dev" environment))

(def dstar-corpora-user
  (get-env "DSTAR_CORPORA_USER"))

(def dstar-corpora-password
  (get-env "DSTAR_CORPORA_PASSWORD"))

(def dstar-corpora-credentials
  (when (and dstar-corpora-user dstar-corpora-password)
    {:basic-auth {:user dstar-corpora-user
                  :pass dstar-corpora-password}}))
