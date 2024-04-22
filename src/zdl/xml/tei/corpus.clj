(ns zdl.xml.tei.corpus
  (:gen-class)
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.tools.cli :as cli]
   [gremid.xml :as gxml]
   [taoensso.timbre :as log]
   [zdl.fs :as fs]
   [zdl.log]
   [zdl.xml]
   [zdl.xml.tei :as tei])
  (:import
   (java.io FilterInputStream FilterOutputStream)
   (java.util Collections)
   (javax.xml XMLConstants)
   (javax.xml.stream XMLEventWriter XMLStreamConstants)
   (javax.xml.stream.events XMLEvent)))

(def cli-opts
  [["-i" "--source DIR" "path to input files (standard input, if not given)"
    :multi true
    :parse-fn io/file
    :default []
    :default-desc "-"
    :update-fn conj]
   ["-f" "--source-filter GLOB" "path patterns to filter contents of source directories"
    :multi true
    :parse-fn (comp fs/path-matcher-fn (partial str "glob:"))
    :default []
    :default-desc "**/*"
    :update-fn conj]
   ["-o" "--target PATH" "path to output file (standard output, if not given)"
    :parse-fn io/file
    :default-desc "-"]
   ["-d" "--debug" "output debugging information to standard error"]
   ["-h" "--help"]])

(defn parse-args
  [args]
  (cli/parse-opts args cli-opts))

(comment
  (parse-args ["--help" "--debug" "annotate"]))

(defn usage
  [options-summary]
  (->>
   ["zdl.xml.tei.corpus - Merging <TEI/> into <teiCorpus/> documents."
    "Copyright (C) 2024 Gregor Middell"
    ""
    "Usage: program-name [options]"
    ""
    "Options:"
    options-summary
    ""
    "This program is free software: you can redistribute it and/or modify"
    "it under the terms of the GNU General Public License as published by"
    "the Free Software Foundation, either version 3 of the License, or"
    "(at your option) any later version."
    ""
    "This program is distributed in the hope that it will be useful,"
    "but WITHOUT ANY WARRANTY; without even the implied warranty of"
    "MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the"
    "GNU General Public License for more details."
    ""
    "You should have received a copy of the GNU General Public License"
    "along with this program. If not, see <https://www.gnu.org/licenses/>."]
   (str/join \newline)))

(defn error-msg
  [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (str/join \newline errors)
       "\n\nSee \"--help\" for usage instructions."))

(defn exit
  ([status]
   (exit status ""))
  ([status msg]
   (when (not-empty msg) (.println System/err msg))
   (System/exit status)))

(def default-source
  (proxy [FilterInputStream] [System/in]
    (close [])
    (toString []
      "<stdin>")))

(def default-target
  (proxy [FilterOutputStream] [System/out]
    (close []
      (.flush ^FilterOutputStream this))
    (toString []
      "<stdout>")))

(defn document-event?
  [^XMLEvent event]
  (let [event-type (.getEventType event)]
    (or (= XMLStreamConstants/START_DOCUMENT event-type)
        (= XMLStreamConstants/END_DOCUMENT event-type)
        (= XMLStreamConstants/DTD event-type))))

(def start-tei-corpus-attrs
  [(.createAttribute gxml/event-factory "xmlns" tei/ns-uri)])

(def start-tei-corpus-event
  (.createStartElement gxml/event-factory
                       XMLConstants/DEFAULT_NS_PREFIX
                       tei/ns-uri
                       "teiCorpus"
                       (.iterator start-tei-corpus-attrs)
                       (Collections/emptyListIterator)))

(def end-tei-corpus-event
  (.createEndElement gxml/event-factory
                     XMLConstants/DEFAULT_NS_PREFIX
                     tei/ns-uri
                     "teiCorpus"))

(defn write-xml-event!
  [^XMLEventWriter writer ^XMLEvent event]
  (.add writer event))

(defn read-sources
  [sources source-filters]
  (let [source? (if (empty? source-filters)
                  (constantly true)
                  (apply some-fn source-filters))]
    (into (sorted-set) (comp (mapcat fs/expand) (filter source?)) sources)))

(defn aggregate
  [sources target]
  (binding [gxml/*input-factory*  zdl.xml/input-factory
            gxml/*output-factory* zdl.xml/output-factory]
    (log/tracef "> %s" target)
    (with-open [output (io/output-stream target)]
      (let [write! (partial write-xml-event! (gxml/event-writer output))]
        (write! zdl.xml/start-document-event)
        (write! start-tei-corpus-event)
        (doseq [source sources]
          (log/tracef "< %s" source)
          (with-open [input (io/input-stream source)]
            (doseq [event (remove document-event? (gxml/read-events input))]
              (write! event))))
        (write! end-tei-corpus-event)
        (write! zdl.xml/end-document-event)))))

(defn -main
  [& args]
  (try
    (let [{:keys [options errors summary]} (parse-args args)]
      (zdl.log/configure! (:debug options))
      (when (:help options)
        (exit 0 (usage summary)))
      (when errors
        (exit 1 (error-msg errors)))
      (let [sources (read-sources (options :source) (options :source-filter))
            sources (cond->> sources (empty? sources) (cons default-source))
            target  (or (options :target) default-target)]
        (aggregate sources target))
      (exit 0))
    (catch Throwable t
      (log/error t "Error while transforming TEI corpora")
      (exit 2))))

(comment
  (aggregate (read-sources [(io/file "test/zdl/nlp/fixture")]
                           [(fs/path-matcher-fn "glob:**/*.xml")])
             (io/file "tei-corpus.test.xml")))
