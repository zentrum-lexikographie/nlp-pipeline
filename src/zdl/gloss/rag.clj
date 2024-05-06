(ns zdl.gloss.rag
  (:require
   [clojure.data.csv :as csv]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [zdl.ddc.corpora :as ddc.corpora]
   [zdl.gloss.article]
   [zdl.gpt :as gpt]
   [zdl.util :refer [form->id]]
   [zdl.nlp.annotate :refer [annotate deduplicate]]
   [zdl.nlp.gdex :as gdex]
   [gremid.xml :as gxml]
   [zdl.xml])
  (:import
   (java.text Normalizer Normalizer$Form)))

(defn gdex-score
  [{[{:keys [gdex]}] :sentences}]
  gdex)

(defn queried-corpus
  [result]
  (get (meta result) :corpus))

(def ^:dynamic *corpora*
  #{"ballsport" "dta_www" "kernbasis" "zeitungenxl" "wikipedia_www" "webmonitor"})

(defn good-examples
  [n q]
  (binding [ddc.corpora/*num-results-per-corpus* n]
    (->> (ddc.corpora/query *corpora* q) (annotate) (deduplicate)
         (filter (comp gdex/good? gdex-score))
         (sort-by gdex-score #(compare %2 %1))
         (vec))))

(defn balanced-examples-by
  [kf vs]
  (->> (group-by kf vs) (vals)
       (mapcat (partial map-indexed #(assoc %2 :rank %1)))
       (sort-by (juxt :rank (comp - gdex-score)))))


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
        examples   (balanced-examples-by queried-corpus examples)
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

(def soccer-samples
  #_(->>
     (concat
      (with-open [r (io/reader (io/file "/home/gregor/Downloads/EWA_Neu_Prio3 - EWA_Neu.csv"))]
        (into [] (comp (drop 1) (map second))
              (csv/read-csv r)))
      (with-open [r (io/reader (io/file "/home/gregor/Downloads/MWA_Neu_Prio3 - MWA_Neu.csv"))]
        (into [] (comp (drop 1)
                       (filter (comp #{"x"} str/lower-case second))
                       (map #(nth % 5)))
              (csv/read-csv r))))
     (into [] (map (juxt identity term->query))))
  [["Abseitstreffer" "\"Abseitstreffer|lemma\""]
   ["Ankerspieler" "\"Ankerspieler|lemma\""]
   ["Ankerstürmer" "\"Ankerstürmer|lemma\""]
   ["Anspielstation" "\"Anspielstation|lemma\""]
   ["Außenpfosten" "\"Außenpfosten|lemma\""]
   ["Ballbehauptung" "\"Ballbehauptung|lemma\""]
   ["ballsicher" "\"ballsicher|lemma\""]
   ["Bankdrücker" "\"Bankdrücker|lemma\""]
   ["Chancentod" "\"Chancentod|lemma\""]
   ["Chip-Ball" "\"Chip-Ball|lemma\""]
   ["Diagonalflanke" "\"Diagonalflanke|lemma\""]
   ["Direktablage" "\"Direktablage|lemma\""]
   ["Direktpassspiel" "\"Direktpassspiel|lemma\""]
   ["drauftreten" "\"drauftreten|lemma\""]
   ["Dreier-Abwehrkette" "\"Dreier-Abwehrkette|lemma\""]
   ["Dreierblock" "\"Dreierblock|lemma\""]
   ["drüberhauen" "\"drüberhauen|lemma\""]
   ["Einwurfflanke" "\"Einwurfflanke|lemma\""]
   ["Elferschießen" "\"Elferschießen|lemma\""]
   ["Ellenbogencheck" "\"Ellenbogencheck|lemma\""]
   ["Ellbogencheck" "\"Ellbogencheck|lemma\""]
   ["Ergebnisfußballer" "\"Ergebnisfußballer|lemma\""]
   ["Eskortkind" "\"Eskortkind|lemma\""]
   ["Eskortenkind" "\"Eskortenkind|lemma\""]
   ["Fahnenpass" "\"Fahnenpass|lemma\""]
   #_["Faller" "\"Faller|lemma\""]
   ["Fernduell" "\"Fernduell|lemma\""]
   ["festdribbeln" "\"festdribbeln|lemma\""]
   ["festspielen" "\"festspielen|lemma\""]
   ["Filigrantechniker" "\"Filigrantechniker|lemma\""]
   ["Flatterball" "\"Flatterball|lemma\""]
   ["Flügelposition" "\"Flügelposition|lemma\""]
   ["freisperren" "\"freisperren|lemma\""]
   ["Freistoßflanke" "\"Freistoßflanke|lemma\""]
   ["Frustfoul" "\"Frustfoul|lemma\""]
   ["Fünf-Meter-Raum" "\"Fünf-Meter-Raum|lemma\""]
   ["gelbbelastet" "\"gelbbelastet|lemma\""]
   ["gelbgesperrt" "\"gelbgesperrt|lemma\""]
   ["Gelb-Rot" "\"Gelb-Rot|lemma\""]
   ["gestaffelt" "\"gestaffelt|lemma\""]
   ["Halbposition" "\"Halbposition|lemma\""]
   ["Heimschiedsrichter" "\"Heimschiedsrichter|lemma\""]
   ["Heimschlappe" "\"Heimschlappe|lemma\""]
   ["herunterpflücken" "\"herunterpflücken|lemma\""]
   ["Innenraumverbot" "\"Innenraumverbot|lemma\""]
   ["Instinktfußballer" "\"Instinktfußballer|lemma\""]
   ["Keilstürmer" "\"Keilstürmer|lemma\""]
   ["Kick & Rush" "\"Kick|lemma \\&|lemma Rush|lemma\""]
   ["Klein-Klein-Spiel" "\"Klein-Klein-Spiel|lemma\""]
   ["kombinationssicher" "\"kombinationssicher|lemma\""]
   ["Konterspiel" "\"Konterspiel|lemma\""]
   ["Kontertor" "\"Kontertor|lemma\""]
   ["Konzeptfußball" "\"Konzeptfußball|lemma\""]
   ["Kopfballaufsetzer" "\"Kopfballaufsetzer|lemma\""]
   ["Kopfball-Rückgabe" "\"Kopfball-Rückgabe|lemma\""]
   ["Kopfballvorlage" "\"Kopfballvorlage|lemma\""]
   ["Kreativspiel" "\"Kreativspiel|lemma\""]
   ["Kreativspieler" "\"Kreativspieler|lemma\""]
   ["Kullerball" "\"Kullerball|lemma\""]
   ["Lattenabpraller" "\"Lattenabpraller|lemma\""]
   ["Lattenknaller" "\"Lattenknaller|lemma\""]
   ["Lattenkracher" "\"Lattenkracher|lemma\""]
   ["Lattentreffer" "\"Lattentreffer|lemma\""]
   ["Lucky Punch" "\"Lucky|lemma Punch|lemma\""]
   ["Mittelfeldraute" "\"Mittelfeldraute|lemma\""]
   ["Nickligkeiten" "\"Nickligkeiten|lemma\""]
   ["No-Look-Pass" "\"No-Look-Pass|lemma\""]
   ["Notabwehr" "\"Notabwehr|lemma\""]
   ["One-Touch-Fußball" "\"One-Touch-Fußball|lemma\""]
   ["On-Field-Review" "\"On-Field-Review|lemma\""]
   ["Overtime" "\"Overtime|lemma\""]
   ["Passstafette" "\"Passstafette|lemma\""]
   ["Pflichtspielerfolg" "\"Pflichtspielerfolg|lemma\""]
   ["Pflichtspieltor" "\"Pflichtspieltor|lemma\""]
   ["Pfostentreffer" "\"Pfostentreffer|lemma\""]
   ["Pressingopfer" "\"Pressingopfer|lemma\""]
   ["Raumdeuter" "\"Raumdeuter|lemma\""]
   ["rausfischen" "\"rausfischen|lemma\""]
   ["Rechtsfuß" "\"Rechtsfuß|lemma\""]
   ["reindrücken" "\"reindrücken|lemma\""]
   ["reinjagen" "\"reinjagen|lemma\""]
   ["Rumpfelf" "\"Rumpfelf|lemma\""]
   ["Rumpfteam" "\"Rumpfteam|lemma\""]
   ["Sechs-Punkte-Spiel" "\"Sechs-Punkte-Spiel|lemma\""]
   ["semmeln" "\"semmeln|lemma\""]
   ["Sommerfußball" "\"Sommerfußball|lemma\""]
   ["Spielertunnel" "\"Spielertunnel|lemma\""]
   ["Spielsperre" "\"Spielsperre|lemma\""]
   ["Spielverlagerung" "\"Spielverlagerung|lemma\""]
   ["Steckpass" "\"Steckpass|lemma\""]
   ["Strafraumbeherrschung" "\"Strafraumbeherrschung|lemma\""]
   ["Strafraumspieler" "\"Strafraumspieler|lemma\""]
   ["Strafraumszene" "\"Strafraumszene|lemma\""]
   ["Tikitaka" "\"Tikitaka|lemma\""]
   ["Todesgruppe" "\"Todesgruppe|lemma\""]
   ["Torfestival" "\"Torfestival|lemma\""]
   ["Torkonto" "\"Torkonto|lemma\""]
   ["Torwartecke" "\"Torwartecke|lemma\""]
   ["Ultras" "\"Ultras|lemma\""]
   ["Umkehrspiel" "\"Umkehrspiel|lemma\""]
   ["ummähen" "\"ummähen|lemma\""]
   ["umsensen" "\"umsensen|lemma\""]
   ["unbespielbar" "\"unbespielbar|lemma\""]
   ["VAR-Assistent" "\"VAR-Assistent|lemma\""]
   ["verdaddeln" "\"verdaddeln|lemma\""]
   ["Vertikalpass" "\"Vertikalpass|lemma\""]
   ["Vertikalfußball" "\"Vertikalfußball|lemma\""]
   ["Viererblock" "\"Viererblock|lemma\""]
   ["vorbeiköpfen" "\"vorbeiköpfen|lemma\""]
   ["vorbeischieben" "\"vorbeischieben|lemma\""]
   ["vorbeispitzeln" "\"vorbeispitzeln|lemma\""]
   ["Vorlagengeber" "\"Vorlagengeber|lemma\""]
   ["Wechselfehler" "\"Wechselfehler|lemma\""]
   ["Zählbares" "\"Zählbares|lemma\""]
   ["Zeitschinden" "\"Zeitschinden|lemma\""]
   ["Zucker-Pass" "\"Zucker-Pass|lemma\""]
   ["Halbchance" "\"Halbchance|lemma\""]
   ["hellwach" "\"hellwach|lemma\""]
   ["Automatismus" "\"Automatismus|lemma\""]
   ["gesperrt" "\"gesperrt|lemma\""]
   ["herauseilen" "\"herauseilen|lemma\""]
   ["Luftduell" "\"Luftduell|lemma\""]
   ["umgrätschen" "\"umgrätschen|lemma\""]
   ["Volleykracher" "\"Volleykracher|lemma\""]
   ["Doppelsechser" "\"Doppelsechser|lemma\""]
   ["Klein-Klein-Spiel" "\"Klein-Klein-Spiel|lemma\""]
   ["Quali" "\"Quali|lemma\""]
   ["passives Abseits" "\"passives|lemma Abseits|lemma\""]
   ["passive Abseitsstellung" "\"passive|lemma Abseitsstellung|lemma\""]
   ["zweiter Anzug" "\"zweiter|lemma Anzug|lemma\""]
   ["ruhender Ball" "\"ruhender|lemma Ball|lemma\""]
   ["gestrecktes Bein" "\"gestrecktes|lemma Bein|lemma\""]
   ["hohes Bein" "\"hohes|lemma Bein|lemma\""]
   ["kurzes Eck" "\"kurzes|lemma Eck|lemma\""]
   ["langes Eck" "\"langes|lemma Eck|lemma\""]
   ["kurze Ecke" "\"kurze|lemma Ecke|lemma\""]
   ["lange Ecke" "\"lange|lemma Ecke|lemma\""]
   ["falscher Einwurf" "\"falscher|lemma Einwurf|lemma\""]
   ["kleines Finale" "\"kleines|lemma Finale|lemma\""]
   ["direkter Freistoß" "\"direkter|lemma Freistoß|lemma\""]
   ["indirekter Freistoß" "\"indirekter|lemma Freistoß|lemma\""]
   ["freier Mann" "\"freier|lemma Mann|lemma\""]
   ["letzter Mann" "\"letzter|lemma Mann|lemma\""]
   ["zwölfter Mann" "\"zwölfter|lemma Mann|lemma\""]
   ["defensives Mittelfeld" "\"defensives|lemma Mittelfeld|lemma\""]
   ["offensives Mittelfeld" "\"offensives|lemma Mittelfeld|lemma\""]
   ["zentrales Mittelfeld" "\"zentrales|lemma Mittelfeld|lemma\""]
   ["kurzer Pfosten" "\"kurzer|lemma Pfosten|lemma\""]
   ["langer Pfosten" "\"langer|lemma Pfosten|lemma\""]
   ["hohes Pressing" "\"hohes|lemma Pressing|lemma\""]
   ["heiliger Rasen" "\"heiliger|lemma Rasen|lemma\""]
   ["freier Raum" "\"freier|lemma Raum|lemma\""]
   ["ballorientierte Raumdeckung" "\"ballorientierte|lemma Raumdeckung|lemma\""]
   ["zweite Reihe" "\"zweite|lemma Reihe|lemma\""]
   ["goldenes Tor" "\"goldenes|lemma Tor|lemma\""]
   ["direkter Vergleich" "\"direkter|lemma Vergleich|lemma\""]
   ["vierter Offizieller" "\"vierter|lemma Offizieller|lemma\""]])

(defn estimate-query-results
  [q]
  (binding [ddc.corpora/*num-results-per-corpus* 1]
    (->> (ddc.corpora/query *corpora* q) (first) (meta) :total)))

(defn -main
  [& _]
  (doseq [[term q] [["Faller" "\"Faller|lemma\""]]#_(drop 25 soccer-samples)]
    (let [result   (generate gpt/openai term q 1000)
          filename (str (form->id term) ".xml")
          file     (io/file "data" "gloss-samples-soccer" filename)]
      (io/make-parents file)
      (-> (zdl.gloss.article/gloss->xml term (:gloss result) (:examples result))
          (zdl.xml/write-xml file)))))
