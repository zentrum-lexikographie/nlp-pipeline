(ns zdl.nlp.g2p
  (:require
   [taoensso.timbre :as log]
   [zdl.python :as python]
   [babashka.fs :as fs]
   [gremid.xml :as gx]
   [zdl.xml :as xml]
   [clojure.data.csv :as csv]
   [clojure.java.io :as io]))

(when-not (python/installed? "transformers")
  (log/info "Initializing transformers")
  (python/install! "transformers"))

(require '[libpython-clj2.python :as py])

#_:clj-kondo/ignore
(py/from-import transformers AutoTokenizer T5ForConditionalGeneration)

(defonce tokenizer
  #_:clj-kondo/ignore
 (py/py. AutoTokenizer from_pretrained "google/byt5-small"))

(defonce model
  #_:clj-kondo/ignore
  (py/py. T5ForConditionalGeneration from_pretrained
   "charsiu/g2p_multilingual_byT5_tiny_16_layers_100"))

(defn translate
  [tokens]
  #_:clj-kondo/ignore
  (let [encoded     (tokenizer tokens
                           :padding true
                           :add_special_tokens false
                           :return_tensors "pt")
        predictions (py/py. model generate
                            (py/py.- encoded input_ids)
                            :attention_mask (py/py.- encoded attention_mask)
                            :max_length 50)
        decoded     (py/py. tokenizer batch_decode
                           predictions
                           :skip_special_tokens true)]
    (py/->jvm decoded)))

(comment
  (defn extract-pronounciation
    [form]
    (when-let [proncounciation (some->> form (gx/element :Aussprache) (gx/attr :IPA))]
      (when-let [form (some->> form (gx/element :Schreibung) (gx/text))]
        (list [form proncounciation]))))
  
  (->> (fs/glob "../lex/test-data/prod" "**/*.xml")
       (pmap (comp xml/read-xml fs/file))
       (mapcat (partial gx/elements :Formangabe))
       (filter #(= "Hauptform" (gx/attr :Typ %)))
       (mapcat extract-pronounciation)
       (csv/write-csv w)
       (with-open [w (io/writer (fs/file "g2p.csv"))])))
