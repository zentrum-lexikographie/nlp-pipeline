(ns zdl.schema
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [malli.core :as m]
   [malli.transform :as mt]))

(def tagging
  (read-string (slurp (io/resource "zdl/tagging-schema.edn"))))

(def Token
  (into
   [:map
    [:n [:int {:min 0}]]
    [:form :string]
    [:space-after? :boolean]
    [:hit? {:optional true} :boolean]
    [:start {:optional true} [:int {:min 0}]]
    [:end {:optional true} [:int {:min 0}]]
    [:oov? {:optional true} :boolean]
    [:lemma {:optional true} :string]
    [:head {:optional true} [:int {:min 0}]]]
   (map (fn [k] [k {:optional true} (into [:enum nil] (sort (tagging k)))]))
   (sort (keys tagging))))

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
   [:type [:enum nil :collocation :entity]]
   [:label (into [:enum nil] (sort (concat
                                    (tagging :collocation)
                                    (tagging :entity))))]
   [:targets [:vector [:int {:min 0}]]]])

(def Sentence
  [:map
   [:id {:optional true} :string]
   [:text {:optional true} :string]
   [:start {:optional true} [:int {:min 0}]]
   [:end {:optional true} [:int {:min 0}]]
   [:tokens {:optional true} [:vector Token]]
   [:spans {:optional true} [:vector Span]]
   [:gdex {:optional true} [:double]]])

(def transformer
  (mt/string-transformer))

(def encode-sentence
  (m/encoder Sentence transformer))

(def decode-sentence
  (m/decoder Sentence transformer))

(def valid-sentence?
  (m/validator Sentence))

(defn token->text
  [{:keys [form space-after?]}]
  (str form (when space-after? " ")))

(defn sentence->text
  [{:keys [tokens]}]
  (str/join (map token->text tokens)))

(def Chunk
  [:map
   [:sentences [:vector Sentence]]
   [:text {:optional true} :string]
   [:lang {:optional true} :string]
   [:fingerprint {:optional true} :string]])

(def encode-chunk
  (m/encoder Chunk transformer))

(def decode-chunk
  (m/decoder Chunk transformer))

(def valid-chunk?
  (m/validator Chunk))

(def Doc
  [:map
   [:chunks [:vector Chunk]]
   [:text {:optional true} :string]
   [:collection {:optional true} :string]
   [:url {:optional true} :string]
   [:file {:optional true} :string]
   [:bibl {:optional true} :string]
   [:page {:optional true} :string]
   [:author {:optional true} :string]
   [:editor {:optional true} :string]
   [:title {:optional true} :string]
   [:short-title {:optional true} :string]
   [:text-classes {:optional true} [:set :string]]
   [:flags {:optional true} [:set :string]]
   [:access {:optional true} :string]
   [:availability {:optional true} :string]
   [:date {:optional true} :string]
   [:access-date {:optional true} :string]
   [:first-published {:optional true} :string]
   [:country {:optional true} :string]
   [:region {:optional true} :string]
   [:subregion {:optional true} :string]])

(def encode-doc
  (m/encoder Doc transformer))

(def decode-doc
  (m/decoder Doc transformer))

(def valid-doc?
  (m/validator Doc))
