(ns zdl.gloss.rag
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [zdl.ddc.corpora :as ddc.corpora]
   [zdl.gloss.article]
   [zdl.gpt :as gpt]
   [zdl.util :refer [form->id]]
   [zdl.xml]))

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

(def corpora
  #{#_"ballsport" "dta_www" "kernbasis" "zeitungenxl" #_"wikipedia_www" "webmonitor"})

(defn generate
  [gpt term q num-examples]
  (let [examples   (ddc.corpora/good-examples corpora num-examples q)
        examples   (ddc.corpora/balanced-good-examples-by :ddc.corpora/corpus examples)
        examples   (into [] (take 100) examples)
        prompt     (prompt term (map :text examples))
        prompt     [{:role "system" :content personality}
                    {:role "user" :content prompt}]
        completion (gpt prompt :top_p 0.9 :frequency_penalty 1.0)
        tokens     (-> completion :tokens :total)
        answer     (-> completion :choices first :message)
        messages   (conj (completion :messages)
                         {:role "assistant" :content answer})]
    {:messages messages
     :examples examples
     :gloss    answer
     :tokens   tokens}))

(defn dialog->str
  [{:keys [messages tokens]}]
  (-> (->> messages
           (map (fn [{:keys [role content]}] (str "## " role ":\n\n" content)))
           (str/join "\n\n"))
      (str "\n\n––\n" (format "[%,d tokens]" tokens))))

(defn gloss->str
  [{:keys [messages tokens]}]
  (-> (->> messages
           (map (fn [{:keys [role content]}] (str "## " role ":\n\n" content)))
           (str/join "\n\n"))
      (str "\n\n––\n" (format "[%,d tokens]" tokens))))

(defn term->query
  [s]
  (as-> s $
    (str/split $ #"\s+")
    (map #(str % "|lemma") $)
    (str/join \space $)
    (str "\"" $ "\"")))

(comment
  (->> (generate gpt/openai "Kollateralschaden" (term->query "Kollateralschaden") 10)
       (dialog->str)
       (println)))

(defn -main
  [& _]
  (doseq [[term q] [["Faller" "\"Faller|lemma\""]]#_(drop 25 soccer-samples)]
    (let [result   (generate gpt/openai term q 1000)
          filename (str (form->id term) ".xml")
          file     (io/file "data" "gloss-samples-soccer" filename)]
      (io/make-parents file)
      (-> (zdl.gloss.article/gloss->xml term (:gloss result) (:examples result))
          (zdl.xml/write-xml file)))))
