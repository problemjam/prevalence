# Generate `wiktionary.tsv`

## Summary

Build a small Clojure generator for `wiktionary.tsv`.

The generator downloads `pun`'s normalized prevalence dataset from GitHub, so it must not assume `~/dev/pun-data` exists. It also downloads the same raw Wiktionary dump URL used by `pun`: `https://kaikki.org/dictionary/raw-wiktextract-data.jsonl.gz`.

## Output

- Command: `cd clj && clj -M -m build wiktionary`
- Output file: `wiktionary.tsv`
- Columns:
  - `entry`: exact Wiktextract `word` field value, which may be a single word or a phrase
  - `prevalence`: normalized score from `pun-data`
  - `lemma`: `true` iff Wiktextract entry-level or sense-level `categories` contains `English lemmas`
  - `space`: `true` iff `entry` contains an ASCII space

## Implementation

- Add download scripts for generated data inputs:
  - `normalized.edn.gz`: `https://raw.githubusercontent.com/8ta4/pun-data/4b5a2c1eeb992d2c1b8faea2488768eaac6be9dc/normalized.edn.gz`
  - `raw-wiktextract-data.jsonl.gz`: `https://kaikki.org/dictionary/raw-wiktextract-data.jsonl.gz`
- Load `normalized.edn.gz` as an EDN map: `entry -> prevalence`.
- Stream Wiktextract JSONL gzip one record at a time.
- Keep only English records whose exact Wiktextract `word` field value exists in normalized scores.
- Aggregate duplicate raw records by exact entry string, OR-ing the `lemma` flag.
- Sort rows by `prevalence` descending, then `entry` ascending.
- Do not use `vocabulary.txt`, LLM batches, raw score parsing, normalization, or IPA generation.

## Test Plan

- Unit-test lemma and non-lemma records.
- Unit-test entries with and without ASCII spaces.
- Unit-test duplicate aggregation.
- Unit-test TSV escaping and output order.
- Integration-check generated data:
  - no duplicate `entry` values
  - every row has a normalized score
  - missing Wiktextract matches are reported with count and sample keys
  - headers are exactly `entry`, `prevalence`, `lemma`, `space`

## Assumptions

- `prevalence` means the already-normalized recognizability score from `pun-data`.
- `lemma` uses Wiktionary/Wiktextract entry-level category metadata only; no NLP lemmatizer.
- Some Wiktextract records, such as `phone number`, expose `English lemmas` only in sense-level categories, so both entry-level and sense-level categories are checked for `lemma`.
- Wiktextract format reference: `https://github.com/tatuylonen/wiktextract`
