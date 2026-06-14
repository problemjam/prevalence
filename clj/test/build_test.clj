(ns build-test
  (:require [build :as sut]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]])
  (:import [java.io File]
           [java.util.zip GZIPOutputStream]))

(defn temp-file
  [suffix]
  (doto (File/createTempFile "wiktionary-tsv-test" suffix)
    (.deleteOnExit)))

(defn write-gzip-lines!
  [file lines]
  (with-open [out (GZIPOutputStream. (io/output-stream file))
              writer (io/writer out :encoding "UTF-8")]
    (doseq [line lines]
      (.write writer line)
      (.write writer "\n")))
  file)

(deftest aggregates-lemma-and-space-flags
  (let [normalized {"lemma word" 0.7
                    "plain" 0.2
                    "fr-only" 0.9}
        raw-file (write-gzip-lines!
                  (temp-file ".jsonl.gz")
                  [(json/write-str {:word "lemma word"
                                    :lang_code "en"
                                    :categories ["English lemmas"]})
                   (json/write-str {:word "plain"
                                    :lang "English"
                                    :categories []})
                   (json/write-str {:word "fr-only"
                                    :lang_code "fr"
                                    :categories ["English lemmas"]})
                   (json/write-str {:word "not-normalized"
                                    :lang_code "en"
                                    :categories ["English lemmas"]})])
        rows (sut/aggregate-wiktextract raw-file normalized)]
    (is (= #{"lemma word" "plain"} (set (keys rows))))
    (is (true? (:lemma (get rows "lemma word"))))
    (is (false? (:lemma (get rows "plain"))))
    (is (true? (:space (get rows "lemma word"))))
    (is (false? (:space (get rows "plain"))))))

(deftest duplicate-records-or-lemma-flag
  (let [normalized {"run" 0.8}
        raw-file (write-gzip-lines!
                  (temp-file ".jsonl.gz")
                  [(json/write-str {:word "run"
                                    :lang_code "en"
                                    :categories []})
                   (json/write-str {:word "run"
                                    :lang_code "en"
                                    :categories ["English lemmas"]})])
        rows (sut/aggregate-wiktextract raw-file normalized)]
    (is (= 1 (count rows)))
    (is (= {:entry "run"
            :prevalence 0.8
            :lemma true
            :space false}
           (get rows "run")))))

(deftest tsv-escaping-and-output-order
  (let [rows (sut/sort-rows {"z" {:entry "z"
                                  :prevalence 0.2
                                  :lemma false
                                  :space false}
                             "a\t\"b\"" {:entry "a\t\"b\""
                                          :prevalence 0.9
                                          :lemma true
                                          :space false}
                             "a" {:entry "a"
                                  :prevalence 0.9
                                  :lemma false
                                  :space false}})
        out-file (temp-file ".tsv")]
    (sut/write-tsv! out-file rows)
    (is (= "plain" (sut/escape-tsv-field "plain")))
    (is (= "\"line\nbreak\"" (sut/escape-tsv-field "line\nbreak")))
    (is (= ["entry\tprevalence\tlemma\tspace"
            "a\t0.9\tfalse\tfalse"
            "\"a\t\"\"b\"\"\"\t0.9\ttrue\tfalse"
            "z\t0.2\tfalse\tfalse"]
           (str/split-lines (slurp out-file))))))

(deftest generated-data-checks
  (let [normalized {"found" 1.0
                    "lost" 0.1}
        rows-by-entry {"found" {:entry "found"
                                :prevalence 1.0
                                :lemma false
                                :space false}}]
    (is (= ["entry" "prevalence" "lemma" "space"] sut/header))
    (is (= {:missing-count 1
            :sample ["lost"]}
           (sut/missing-summary normalized rows-by-entry 5)))
    (is (= (vals rows-by-entry)
           (sut/validate-rows! normalized (vals rows-by-entry))))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Duplicate entry"
         (sut/validate-rows! normalized
                             [{:entry "found"
                               :prevalence 1.0}
                              {:entry "found"
                               :prevalence 1.0}])))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"normalized score"
         (sut/validate-rows! normalized
                             [{:entry "unknown"
                               :prevalence 1.0}])))))

(defn -main
  [& _args]
  (let [{:keys [fail error]} (run-tests 'build-test)]
    (when (pos? (+ fail error))
      (System/exit 1))))
