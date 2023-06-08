(ns zdl.nlp.tokenizer
  "Segmentation, tokenization and language detection."
  (:import
   (com.github.pemistahl.lingua.api Language LanguageDetector LanguageDetectorBuilder)
   (de.ids_mannheim.korap.tokenizer DerekoDfaTokenizer_de)
   (opennlp.tools.util Span)))

(def ^DerekoDfaTokenizer_de tokenizer
  (DerekoDfaTokenizer_de.))

(defn span->map
  "Turn OpenNLP Span objects into maps."
  ([s span]
   (span->map 0 s span))
  ([offset s ^Span span]
   (let [start (+ offset (.getStart span))
         end   (+ offset (.getEnd span))]
     {:text  (subs s start end)
      :start start
      :end   end})))

(defn segment-sentences
  [s]
  (map (partial span->map s) (.sentPosDetect tokenizer s)))

(def index-tokens-xf
  (map-indexed #(assoc %2 :n %1)))

(defn segment-tokens
  [s {:keys [start text] :as sentence}]
  (assoc sentence :tokens
         (into []
               (comp (map (partial span->map start s)) index-tokens-xf)
               (.tokenizePos tokenizer text))))

(defn assoc-space-after**
  [[{:keys [end] :as token} next-token]]
  (assoc token :space-after? (< end (get next-token :start end))))

(defn assoc-space-after*
  [[{:keys [tokens] :as sentence} {next-tokens :tokens}]]
  (let [tokens      (concat tokens (take 1 next-tokens))
        token-pairs (partition-all 2 1 tokens)
        tokens      (map assoc-space-after** token-pairs)
        tokens      (cond-> tokens (seq next-tokens) (butlast))]
    (assoc sentence :tokens (vec tokens))))

(defn assoc-space-after
  [sentences]
  (let [sentence-pairs (partition-all 2 1 sentences)]
    (map assoc-space-after* sentence-pairs)))

;; ## Language Detection

(def ^LanguageDetector language-detector
  (..
   (LanguageDetectorBuilder/fromLanguages
    (into-array Language [Language/GERMAN Language/ENGLISH Language/FRENCH]))
   (withPreloadedLanguageModels)
   (build)))

(defn detect-language
  [s]
  (let [lang (.detectLanguageOf language-detector s)]
    (.. ^Language lang (getIsoCode639_3) (toString))))

(defn assoc-language
  [{:keys [text] :as sentence}]
  (assoc sentence :language (detect-language text)))

(defn tokenize
  [s & {:keys [detect-language?] :or {detect-language? true}}]
  (let [sentences (map (partial segment-tokens s)  (segment-sentences s))
        sentences (assoc-space-after sentences)]
    (vec (cond->> sentences detect-language? (map assoc-language)))))
