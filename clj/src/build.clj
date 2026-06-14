(ns build
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io PushbackReader]
           [java.net URL]
           [java.nio.file CopyOption Files StandardCopyOption]
           [java.util.zip GZIPInputStream]))

(def normalized-url
  "https://raw.githubusercontent.com/8ta4/pun-data/4b5a2c1eeb992d2c1b8faea2488768eaac6be9dc/normalized.edn.gz")

(def raw-wiktextract-url
  "https://kaikki.org/dictionary/raw-wiktextract-data.jsonl.gz")

(def data-dir (io/file "data"))
(def normalized-file (io/file data-dir "normalized.edn.gz"))
(def raw-wiktextract-file (io/file data-dir "raw-wiktextract-data.jsonl.gz"))
(def output-file (io/file ".." "wiktionary.tsv"))

(def header ["entry" "prevalence" "lemma" "space"])
(def missing-sample-size 20)

(def input-specs
  {:normalized {:url normalized-url
                :file normalized-file}
   :wiktextract {:url raw-wiktextract-url
                 :file raw-wiktextract-file}})

(defn log!
  [& parts]
  (binding [*out* *err*]
    (println (str/join " " parts))))

(defn present-file?
  [file]
  (and (.exists file) (pos? (.length file))))

(defn download-file!
  [url file]
  (io/make-parents file)
  (let [tmp-file (io/file (str (.getPath file) ".part"))]
    (log! "Downloading" url "->" (.getPath file))
    (with-open [in (io/input-stream (URL. url))
                out (io/output-stream tmp-file)]
      (io/copy in out))
    (Files/move (.toPath tmp-file)
                (.toPath file)
                (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING]))
    file))

(defn ensure-file!
  [{:keys [url file]}]
  (if (present-file? file)
    (do
      (log! "Using existing" (.getPath file))
      file)
    (download-file! url file)))

(defn download-input!
  [input-key]
  (let [spec (get input-specs input-key)]
    (when-not spec
      (throw (ex-info "Unknown input key" {:input-key input-key
                                           :known-inputs (keys input-specs)})))
    (ensure-file! spec)))

(defn download-inputs!
  []
  (doseq [input-key [:normalized :wiktextract]]
    (download-input! input-key)))

(defn load-normalized
  [file]
  (with-open [in (GZIPInputStream. (io/input-stream file))
              rdr (PushbackReader. (io/reader in :encoding "UTF-8"))]
    (let [scores (edn/read rdr)]
      (when-not (map? scores)
        (throw (ex-info "normalized.edn.gz must contain an EDN map"
                        {:file (.getPath file)
                         :type (type scores)})))
      scores)))

(defn english-record?
  [{:keys [lang lang_code]}]
  (if (some? lang_code)
    (= "en" lang_code)
    (= "English" lang)))

(defn category-name
  [category]
  (cond
    (string? category) category
    (map? category) (or (:name category) (:category category) (:title category))
    :else nil))

(defn lemma-record?
  [record]
  (boolean
   (or (some #(= "English lemmas" (category-name %))
             (:categories record))
       (some (fn [sense]
               (some #(= "English lemmas" (category-name %))
                     (:categories sense)))
             (:senses record)))))

(defn row-for
  [entry prevalence lemma?]
  {:entry entry
   :prevalence prevalence
   :lemma lemma?
   :space (str/includes? entry " ")})

(defn aggregate-record
  [normalized rows record]
  (let [entry (:word record)]
    (if (and (string? entry)
             (english-record? record)
             (contains? normalized entry))
      (let [lemma? (lemma-record? record)
            prevalence (get normalized entry)]
        (update rows entry
                (fn [row]
                  (if row
                    (update row :lemma #(or % lemma?))
                    (row-for entry prevalence lemma?)))))
      rows)))

(defn aggregate-wiktextract
  [file normalized]
  (with-open [in (GZIPInputStream. (io/input-stream file))
              rdr (io/reader in :encoding "UTF-8")]
    (reduce (fn [rows line]
              (if (str/blank? line)
                rows
                (aggregate-record normalized
                                  rows
                                  (json/read-str line :key-fn keyword))))
            {}
            (line-seq rdr))))

(defn row-compare
  [left right]
  (let [prevalence-order (compare (:prevalence right) (:prevalence left))]
    (if (zero? prevalence-order)
      (compare (:entry left) (:entry right))
      prevalence-order)))

(defn sort-rows
  [rows-by-entry]
  (sort row-compare (vals rows-by-entry)))

(defn escape-tsv-field
  [value]
  (let [s (str value)]
    (if (some #(str/includes? s %) ["\"" "\t" "\n" "\r"])
      (str "\"" (str/replace s "\"" "\"\"") "\"")
      s)))

(defn row->fields
  [row]
  [(:entry row) (:prevalence row) (:lemma row) (:space row)])

(defn write-tsv!
  [file rows]
  (io/make-parents file)
  (with-open [writer (io/writer file :encoding "UTF-8")]
    (doseq [fields (cons header (map row->fields rows))]
      (.write writer (str (str/join "\t" (map escape-tsv-field fields)) "\n"))))
  file)

(defn missing-summary
  [normalized rows-by-entry sample-size]
  (reduce-kv
   (fn [{:keys [missing-count sample] :as summary} entry _prevalence]
     (if (contains? rows-by-entry entry)
       summary
       {:missing-count (inc missing-count)
        :sample (if (< (count sample) sample-size)
                  (conj sample entry)
                  sample)}))
   {:missing-count 0
    :sample []}
   normalized))

(defn report-missing!
  [{:keys [missing-count sample]}]
  (if (zero? missing-count)
    (log! "Missing Wiktextract matches: 0")
    (log! "Missing Wiktextract matches:" missing-count "sample keys:" (pr-str sample))))

(defn validate-rows!
  [normalized rows]
  (let [entries (map :entry rows)]
    (when-not (= (count entries) (count (distinct entries)))
      (throw (ex-info "Duplicate entry values in generated rows" {})))
    (when-let [entry (some #(when-not (contains? normalized (:entry %)) (:entry %))
                           rows)]
      (throw (ex-info "Generated row does not have a normalized score"
                      {:entry entry})))
    rows))

(defn build-wiktionary!
  []
  (download-inputs!)
  (let [normalized (load-normalized normalized-file)
        rows-by-entry (aggregate-wiktextract raw-wiktextract-file normalized)
        rows (sort-rows rows-by-entry)]
    (validate-rows! normalized rows)
    (report-missing! (missing-summary normalized rows-by-entry missing-sample-size))
    (write-tsv! output-file rows)
    (log! "Wrote" (.getPath output-file) "rows:" (count rows))))

(defn usage!
  []
  (log! "Usage: clj -M -m build wiktionary")
  (log! "       clj -M -m build download-inputs")
  (log! "       clj -M -m build download-normalized")
  (log! "       clj -M -m build download-wiktextract")
  (System/exit 2))

(defn -main
  [& args]
  (case (first args)
    "wiktionary" (build-wiktionary!)
    "download-inputs" (download-inputs!)
    "download-normalized" (download-input! :normalized)
    "download-wiktextract" (download-input! :wiktextract)
    (usage!)))
