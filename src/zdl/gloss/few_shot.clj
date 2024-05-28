(ns zdl.gloss.few-shot
  (:require
   [clojure.string :as str]
   [zdl.gpt :as gpt]))

(def sample-glosses
  [{:form    "Arzt",
    :pos     "Substantiv",
    :glosses [(str "auf einer Hochschule ausgebildeter, approbierter Fachmann "
                   "auf dem Gebiet der Medizin")]}
   {:form    "Epiphanie",
    :pos     "Substantiv",
    :glosses [(str "das (unerwartete) Erscheinen, Sichoffenbaren eines Gottes "
                   "unter den Menschen")
              "die Sichtbarwerdung Jesu als Gottheit"
              (str "unverhoffte Erscheinung, besonderes Ereignis, Offenbarung "
                   "von etwas Außergewöhnlichem")]}
   {:form    "beteiligen",
    :pos     "Verb",
    :glosses ["an etw. mitwirken, teilnehmen, teilhaben"
              "Unternehmensanteile erwerben, als Teilhaber besitzen"
              "Teilnahme oder Mitwirkung gewähren"
              "jmdn. bei der Verteilung von Gewinnen berücksichtigen"
              "jmdn. zum Tragen von Kosten heranziehen"]}
   {:form    "dribbeln",
    :pos     "Verb",
    :glosses [(str "den Ball durch Dribbling, mit kurzen, schnellen Stößen "
                   "(und mit Antäuschungen) am gegnerischen Spieler vorbei "
                   "vorantreiben")]}
   {:form    "etw. zur Folge haben",
    :pos     "Mehrwortausdruck",
    :glosses ["etw. auslösen, bewirken, verursachen"
              "etw. nach sich ziehen"]}
   {:form    "etw. auf Vordermann halten",
    :pos     "Mehrwortausdruck",
    :glosses [(str "dafür sorgen, dass ein Gebäude, eine Anlage o. Ä. modern, "
                   "funktionsfähig, gut ausgestattet bleibt")]}])

(defn form->user-message
  [form]
  {:role    "user"
   :content (format "Was bedeutet \"%s\"?" form)})

(defn gloss->chat
  [{:keys [form glosses]}]
  [(form->user-message form)
   {:role "assistant"
    :content (if (second glosses)
               (->> glosses
                    (map-indexed #(format "%d. %s" (inc %1) %2))
                    (str/join \newline))
               (first glosses))}])

(def personality
  "Du bist ein Lexikograph und gibst kurze, genaue Antworten!")

(def prompt
  (into [{:role "system" :content personality}]
        (mapcat gloss->chat)
        sample-glosses))

(defn generate
  [gpt term]
  (let [prompt     (conj prompt (form->user-message term))
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
  (println (generate gpt/openai "Schöffengericht")))
