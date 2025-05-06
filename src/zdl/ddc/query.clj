(ns zdl.ddc.query
  (:require
   [clojure.string :as str]
   [strojure.parsesso.char :as char]
   [strojure.parsesso.parser :as p]))

;; ## Escaping of special chars in regexps, terms etc.

(defn escape
  [char-re s]
  (str/replace s char-re #(str "\\" %)))

(defn unescape
  [char-re]
  (let [re (re-pattern (str "\\\\" char-re))]
    (fn [s] (str/replace s re #(subs % 1 2)))))

(def escape-quoted
  (partial escape #"\""))

(def unescape-quoted
  (unescape "\""))

(def escape-regexp
  (partial escape #"/"))

(def unescape-regexp
  (unescape "/"))

(def escape-pattern
  (partial escape #"[\!\(\)\:\^\[\]\"\{\}\~\\]"))

(def unescape-pattern
  (unescape "[\\!\\(\\)\\:\\^\\[\\]\\\"\\{\\}\\~\\\\]"))

(def escape-term
  (comp (partial escape #"[\*\?]") escape-pattern))

(def unescape-term
  (comp unescape-pattern (unescape "[\\*\\?]")))

;; ## Parsesso-based parser
;;
;; cf. https://www.cudmuncher.de/~moocow/software/ddc/querydoc.html

(defn tagged
  [tag v]
  {:tag     tag
   :content (list v)})

(def whitespace-chars
  " \t\n\r\u3000")

(def escape-chars
  "\\")

(def form-chars
  "@")

(def group-chars
  "()")

(def index-chars
  "$")

(def quoted-term-chars
  "'")

(def term-set-chars
  (str "{}"))

(def list-sep-chars
  (str ","))

(def phrase-chars
  "\"")

(def infix-chars
  "*")

(def regex-chars
  "/")

(def regex-flag-chars
  "dgix")

(def term-expan-chars
  "|")

(def filter-chars
  "#")

(def subcorpus-directive-chars
  ":")

(def *ws
  (p/*many (char/is whitespace-chars)))


(def +ws
  (p/+many (char/is whitespace-chars)))

(def escaped
  (-> (p/group (char/is escape-chars) (char/is-not whitespace-chars))
      (p/value (fn [[_ c]] (str c)))))

(def term-rest-chars
  (str whitespace-chars group-chars phrase-chars
       infix-chars phrase-chars escape-chars
       term-expan-chars term-set-chars list-sep-chars))

(def term-start-chars
  (str term-rest-chars regex-chars form-chars index-chars
       filter-chars subcorpus-directive-chars))


(def term-start
  (p/alt (char/is-not term-start-chars) escaped))


(def term-rest
  (p/alt (char/is-not term-rest-chars) escaped))


(def unquoted-term
  (p/group term-start (p/*many term-rest)))

(def quoted-term
  (->
   (p/*many (p/alt escaped (char/is-not quoted-term-chars)))
   (p/between (char/is quoted-term-chars))))

(def expanders
  {"case"      "case"
   "www"       "www"
   "eqlemma"   "eqlemma"
   "eql"       "eqlemma"
   "id"        "id"
   "null"      "id"
   "lemma"     "lemma"
   "lemmatize" "lemma"
   "lemmata"   "lemmata"
   "lemmas"    "lemmata"})

(def term-expansions
  (-> (p/group (char/is term-expan-chars)
               (p/+sep-by
                (apply p/alt (map p/word (keys expanders)))
                (char/is term-expan-chars)))
      (p/value second (partial map #(get expanders % %)))))

(def term
  (->
   (p/alt quoted-term unquoted-term)
   (p/group (p/option term-expansions))
   (p/value
    (fn [[term expansions]]
      (cond-> (tagged :term (char/str* term))
        expansions (assoc :attrs {:expansions (vec expansions)}))))))

(def term-set
  (->
   (p/+sep-by term (p/+many (char/is list-sep-chars)))
   (p/between (char/is "{") (char/is "}"))
   (p/value (partial tagged :term-set))))

(def form
  (-> (p/group (char/is form-chars) (p/alt term-set term))
      (p/value second (partial tagged :form))))

(def infix
  (-> (p/between term (char/is infix-chars))
      (p/value (partial tagged :infix))))

(def suffix
  (-> (p/group (char/is infix-chars) term)
      (p/value second (partial tagged :suffix))))

(def prefix
  (-> (p/group term (char/is infix-chars))
      (p/value first (partial tagged :prefix))))

(def match-all
  (-> (char/is infix-chars) (p/value (constantly {:tag :all}))))

(def regex
  (->
   (p/*many (p/alt escaped (char/is-not regex-chars)))
   (p/between (char/is regex-chars))
   (p/group (p/option (p/+many (char/is regex-flag-chars))))
   (p/value
    (fn [[regex flags]]
      (cond-> (tagged :re (char/str* regex))
        flags (assoc :attrs {:flags (into #{} flags)}))))))

(def token
  (p/alt (p/maybe prefix) (p/maybe infix) (p/maybe suffix) match-all
         term-set form regex term))

(def clause
  token)

(def hits-flag
  (->
   (->>
    (mapcat #(list (str "#" %) (str "#no" %)) ["join" "separate" "sep"])
    (map #(p/maybe (p/word % :ic)))
    (apply p/alt))
   (p/value
    (fn [flag]
      {:tag   :hits
       :attrs {:separate (str (cond->> (str/includes? flag "sep")
                                (str/includes? flag "no") (not)))}}))))

(def contexts
  {"sentence"  "s"
   "s"         "s"
   "paragraph" "p"
   "p"         "p"
   "file"      "file"})

(def context-flag
  (->
   (->>
    (mapcat #(list (str "#within " %) (str "#in " %)) (keys contexts))
    (map #(p/maybe (p/word % :ic)))
    (apply p/alt))
   (p/value
    (fn [context]
      (let [context (str/replace context #"^#(with)?in " "")]
        {:tag   :within
         :attrs {:context (get contexts context context)}})))))

(comment
  (p/parse token "{test|www, testen,testet, 'testete'}")
  (p/parse token "'tes.'*")
  (p/parse token "@\\@test")
  (p/parse token "/^te.*st/")
  (p/parse token "*'ts\\'t'")
  (p/parse token "'tes.'*"))

(p/word "has" :ic)

(def subcorpora
  (-> (p/group (char/is subcorpus-directive-chars)
               (p/+sep-by term (char/is list-sep-chars)))
      (p/value (fn [[_ subcorpora]] {:tag     :subcorpora
                                       :content subcorpora}))))

(def query-component
  (p/alt subcorpora
         hits-flag
         context-flag
         clause))

(def query
  (p/+sep-by query-component +ws))
