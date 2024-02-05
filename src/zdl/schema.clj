(ns zdl.schema
  (:require
   [camel-snake-kebab.core :as csk]
   [malli.core :as m]
   [malli.transform :as mt]))

(def key-keyword
  (memoize #(some-> % csk/->kebab-case-keyword)))

(def tag-str
  (memoize #(some-> % csk/->SCREAMING_SNAKE_CASE_STRING)))

(def tag-coding
  {:decode/string tag-str
   :encode/string tag-str})

(def Token
  [:map
   [:n [:int {:min 0}]]
   [:form :string]
   [:space-after? :boolean]
   [:start {:optional true} [:int {:min 0}]]
   [:end {:optional true} [:int {:min 0}]]
   [:oov? {:optional true} :boolean]
   [:lemma {:optional true} :string]
   [:head {:optional true} [:int {:min 0}]]
   [:deprel {:optional true} [:string tag-coding]]
   [:upos {:optional true} [:string tag-coding]]
   [:xpos {:optional true} [:string tag-coding]]
   [:case {:optional true} [:string tag-coding]]
   [:degree {:optional true} [:string tag-coding]]
   [:gender {:optional true} [:string tag-coding]]
   [:mood {:optional true} [:string tag-coding]]
   [:number {:optional true} [:string tag-coding]]
   [:person {:optional true} [:string tag-coding]]
   [:tense {:optional true} [:string tag-coding]]
   [:adp-type {:optional true} [:string tag-coding]]
   [:conj-type {:optional true} [:string tag-coding]]
   [:num-type {:optional true} [:string tag-coding]]
   [:part-type {:optional true} [:string tag-coding]]
   [:pron-type {:optional true} [:string tag-coding]]
   [:punct-type {:optional true} [:string tag-coding]]
   [:verb-type {:optional true} [:string tag-coding]]
   [:verb-form {:optional true} [:string tag-coding]]
   [:definite {:optional true} [:string tag-coding]]])

(defn assoc-space-after**
  [[t1 t2]]
  (assoc t1 :space-after? (or (some-> t2 :space-before? true?) false)))

(defn assoc-space-after*
  [[{:keys [tokens] :as sentence} {next-tokens :tokens}]]
  (let [tokens      (concat tokens (take 1 next-tokens))
        token-pairs (partition-all 2 1 tokens)
        tokens      (map assoc-space-after** token-pairs)
        tokens      (cond-> tokens (seq next-tokens) (butlast))
        tokens      (map #(dissoc % :space-before?) tokens)]
    (assoc sentence :tokens (vec tokens))))

(defn assoc-space-after
  [sentences]
  (let [sentence-pairs (partition-all 2 1 sentences)]
    (map assoc-space-after* sentence-pairs)))


(def Span
  [:map
   [:label [:string tag-coding]]
   [:targets [:vector [:int {:min 1}]]]])

(def Sentence
  [:map
   [:id {:optional true} :string]
   [:text {:optional true} :string]
   [:start {:optional true} [:int {:min 0}]]
   [:end {:optional true} [:int {:min 0}]]
   [:tokens {:optional true} [:vector Token]]
   [:entities {:optional true} [:vector Span]]
   [:collocations {:optional true} [:vector Span]]])

(def transformer
  (mt/string-transformer))

(def encode-sentence
  (m/encoder Sentence transformer))

(def decode-sentence
  (m/decoder Sentence transformer))

(def valid-sentence?
  (m/validator Sentence))
