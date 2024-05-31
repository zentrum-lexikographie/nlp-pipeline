(ns zdl.nlp
  (:require
   [zdl.nlp.deps :as deps]
   [zdl.nlp.dwdsmor :as dwdsmor]
   [zdl.nlp.gdex :as gdex]
   [zdl.nlp.hash :as hash]
   [zdl.nlp.langid :as langid]
   [zdl.nlp.spacy :as spacy]
   [zdl.nlp.tokenizer :as tokenizer]
   [taoensso.timbre :as log]))

(defn update-throughput!
  [{:keys [chunks chars sentences tokens]} {:keys [text] sentences* :sentences}]
  {:chunks    (inc chunks)
   :chars     (+ chars (count text))
   :sentences (+ sentences (count sentences*))
   :tokens    (+ tokens (count (mapcat :tokens sentences*)))})

(defn avg-per-sec
  [secs n]
  (if (pos? secs) (float (/ n secs)) 0.0))

(def log-throughput-format
  "%,12d/%,10.2f <p> %,12d/%,10.2f <s> %,12d/%,10.2f <w> %,12d/%,10.2f <c>")

(defn log!
  [start stats]
  (let [millis      (- (System/currentTimeMillis) start)
        avg-per-sec (partial avg-per-sec (/ millis 1000))
        stats       (deref stats)
        chunks      (:chunks stats)
        chars       (:chars stats)
        sentences   (:sentences stats)
        tokens      (:tokens stats)]
    (log/infof log-throughput-format
               chunks    (avg-per-sec chunks)
               sentences (avg-per-sec sentences)
               tokens    (avg-per-sec tokens)
               chars     (avg-per-sec chars))))

(defn log-xf
  [rf]
  (let [start   (System/currentTimeMillis)
        next*   (volatile! (System/currentTimeMillis))
        log?    #(<=  @next* (System/currentTimeMillis))
        next!   #(vreset! next* (+ (System/currentTimeMillis) 10000))
        stats   (volatile! {:chunks 0 :chars 0 :sentences 0 :tokens 0})
        update! #(vswap! stats update-throughput! %)
        log!    (partial log! start stats)]
    (fn
      ([] (rf))
      ([result]
       (log!)
       (rf result))
      ([result c]
       (update! c)
       (when (log?) (log!) (next!))
       (rf result c)))))

(defn unfold-layer
  [k i v]
  (for [c (v k)]  (assoc c ::parent i)))

(defn fold-layer
  [k parent contents]
  (assoc parent k (into [] (map #(dissoc % ::parent)) contents)))

(defn annotate-layer
  [k annotate-fn coll]
  (->> coll
       (map-indexed (partial unfold-layer k))
       (mapcat identity)
       (annotate-fn)
       (partition-by ::parent)
       (map (partial fold-layer k) coll)))

(def annotate-tokens
  (partial annotate-layer :tokens (partial map dwdsmor/lemmatize)))

(def annotate-sentences
  (partial
   annotate-layer
   :sentences
   (comp (partial pmap (comp deps/analyze-collocations gdex/score))
         annotate-tokens)))

(def annotate-chunks
  (comp
   (partial sequence log-xf)
   (partial pmap (comp langid/detect-lang hash/fingerprint))
   annotate-sentences
   spacy/tagged-seq))

(def annotate-docs
  (partial annotate-layer :chunks annotate-chunks))

(defn tokenize-chunk
  [{:keys [text sentences] :as chunk}]
  (cond
    sentences chunk
    text      (merge (tokenizer/tokenize text) chunk)
    :else     (throw (IllegalArgumentException. (str chunk)))))

(def tokenize-chunks
  (partial map tokenize-chunk))

(def process-chunks
  (comp annotate-chunks tokenize-chunks))

(def tokenize-docs
  (partial annotate-layer :chunks tokenize-chunks))

(def process-docs
  (comp annotate-docs tokenize-docs))
