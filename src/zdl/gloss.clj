(ns zdl.gloss
  (:require
   [clojure.data.csv :as csv]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [zdl.ddc.corpora :as ddc.corpora]
   [zdl.gpt :as gpt]
   [zdl.nlp.annotate :refer [annotate deduplicate]]
   [zdl.nlp.gdex :as gdex])
  (:import
   (java.text Normalizer Normalizer$Form)))

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
  [gpt term q num-examples]
  (let [examples   (good-examples num-examples q)
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

(defn term->query
  [s]
  (as-> s $
    (str/split $ #"\s+")
    (map #(str % "|lemma") $)
    (str/join \space $)
    (str "\"" $ "\"")))

(def soccer-samples
  #_(with-open [r (io/reader (io/file "data" "gloss-samples-soccer.csv"))]
      (into [] (comp (drop 1) (map second) (map (juxt identity term->query)))
            (csv/read-csv r)))
  [["Kopfball-Rückgabe" "\"Kopfball-Rückgabe|lemma\""]
   ["Ankerspieler" "\"Ankerspieler|lemma\""]
   ["Chancentod" "\"Chancentod|lemma\""]
   ["drauftreten" "\"drauftreten|lemma\""]
   ["freisperren" "\"freisperren|lemma\""]
   ["Keilstürmer" "\"Keilstürmer|lemma\""]
   ["Kick & Rush" "\"Kick|lemma \\&|lemma Rush|lemma\""]
   ["Konzeptfußball" "\"Konzeptfußball|lemma\""]
   ["Doppelsechser" "\"Doppelsechser|lemma\""]
   ["No-Look-Pass" "\"No-Look-Pass|lemma\""]
   ["One-Touch-Fußball" "\"One-Touch-Fußball|lemma\""]
   ["Sechs-Punkte-Spiel" "\"Sechs-Punkte-Spiel|lemma\""]
   ["Strafraumspieler" "\"Strafraumspieler|lemma\""]
   ["Halbchance" "\"Halbchance|lemma\""]
   ["Torwartecke" "\"Torwartecke|lemma\""]
   ["herauseilen" "\"herauseilen|lemma\""]
   ["Tikitaka" "\"Tikitaka|lemma\""]
   ["verdaddeln" "\"verdaddeln|lemma\""]
   ["Steckpass" "\"Steckpass|lemma\""]
   ["Sommerfußball" "\"Sommerfußball|lemma\""]
   ["gestrecktes Bein" "\"gestrecktes|lemma Bein|lemma\""]
   ["falscher Einwurf" "\"falscher|lemma Einwurf|lemma\""]
   ["ruhender Ball" "\"ruhender|lemma Ball|lemma\""]
   ["vierter Offizieller" "\"vierter|lemma Offizieller|lemma\""]
   ["passive Abseitsstellung" "\"passive|lemma Abseitsstellung|lemma\""]
   ["kurzes Eck" "\"kurzes|lemma Eck|lemma\""]
   ["langes Eck" "\"langes|lemma Eck|lemma\""]
   ["hohes Bein" "\"hohes|lemma Bein|lemma\""]
   ["taktisches Foul" "\"taktisches|lemma Foul|lemma\""]
   ["letzter Mann" "\"letzter|lemma Mann|lemma\""]
   ["indirekter Freistoß" "\"indirekter|lemma Freistoß|lemma\""]
   ["dreckiger Sieg" "\"dreckiger|lemma Sieg|lemma\""]
   ["lupenreiner Hattrick" "\"lupenreiner|lemma Hattrick|lemma\""]
   ["goldenes Tor" "\"goldenes|lemma Tor|lemma\""]
   ["passives Abseits" "\"passives|lemma Abseits|lemma\""]
   ["zwölfter Mann" "\"zwölfter|lemma Mann|lemma\""]
   ["kurzer Pfosten" "\"kurzer|lemma Pfosten|lemma\""]
   ["langer Pfosten" "\"langer|lemma Pfosten|lemma\""]
   ["hohes Pressing" "\"hohes|lemma Pressing|lemma\""]
   ["direkter Freistoß" "\"direkter|lemma Freistoß|lemma\""]])

(defn estimate-query-results
  [q]
  (binding [ddc.corpora/*num-results-per-corpus* 1]
    (->> (ddc.corpora/query corpora q) (first) (meta) :total)))

(defn term->basename
  [form]
  (-> form
      (Normalizer/normalize Normalizer$Form/NFD)
      (str/replace #"\p{InCombiningDiacriticalMarks}" "")
      (str/replace "ß" "ss")
      (str/replace " " "_")
      (str/replace #"[^\p{Alpha}\p{Digit}\-_]" "_")
      (str/replace #"_+" "_")))

(comment
  (map (juxt first (comp term->basename first)) soccer-samples))

(defn -main
  [& _]
  (doseq [[term q] (drop 2 soccer-samples)]
    (let [filename (str (term->basename term) ".md")
          file     (io/file "data" "gloss-samples-soccer" filename)]
      (io/make-parents file)
      (spit file (generate gpt/openai term q 10000)))))
