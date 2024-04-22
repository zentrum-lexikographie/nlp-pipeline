(ns zdl.gloss
  (:require [zdl.ddc.corpora :as ddc.corpora]
            [zdl.nlp.annotate :refer [annotate deduplicate]]
            [zdl.nlp.gdex :as gdex]
            [zdl.gpt :as gpt]
            [clojure.string :as str]
            [clojure.java.io :as io]))

(defn gdex-score
  [{[{:keys [gdex]}] :sentences}]
  gdex)

(def corpora
  #{"ballsport"})

(defn good-examples
  [n q]
  (binding [ddc.corpora/*num-results-per-corpus* n]
    (->> (ddc.corpora/query corpora q) (annotate) (deduplicate)
         (filter (comp gdex/good? gdex-score))
         (sort-by gdex-score #(compare %2 %1))
         (vec))))

(def personality
  "Du bist ein Lexikograph und gibst kurze, genaue Antworten!")

(defn prompt
  [term examples]
  (str "Analysiere die folgenden Beispielsätze, um eine "
       "knappe und präzise Definition des Begriffs \"" term "\" zu "
       "erarbeiten. Ermittle zunächst alle Bedeutungen, die sich aus "
       "dem Verwendungskontext von \"" term "\" in den Beispielsätzen ergeben. "
       "Formuliere daraus abgeleitet eine Definition, "
       "die die Bedeutungen genau wiedergibt. Achte auf Klarheit, Prägnanz "
       "und Relevanz, um die Wirksamkeit der Definition zu gewährleisten.\n\n"
       "Beispielsätze:\n\n"
       (->> (map-indexed (fn [n s] (str (inc n) ". " s)) examples)
            (str/join "\n\n"))))

(defn generate
  [gpt term num-examples]
  (let [examples   (good-examples num-examples term)
        prompt     (prompt term (take 100 (map :text examples)))
        prompt     [{:role "system" :content personality}
                    {:role "user" :content prompt}]
        completion (gpt prompt :top_p 0.9 :frequency_penalty 1.0)
        tokens     (-> completion :tokens :total)
        answer     (-> completion :choices first :message)
        messages   (conj (completion :messages)
                         {:role "assistant" :content answer})]
    (-> (->> messages
             (map (fn [{:keys [role content]}] (str "## " role ":\n\n" content)))
             (str/join "\n\n"))
        (str "\n\n––\n" (format "[%,d tokens]" tokens)))))


(comment
  (spit (io/file "ecke.md") (generate gpt/openai "Abseits" 100)))
