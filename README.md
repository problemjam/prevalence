# prevalence

## Word on the Street

> What is this tool about?

`prevalence` is a pipeline that scores Wiktionary and Wikipedia entries by estimating how widely known their meanings are.

## Wiktionary TSV

Generate `wiktionary.tsv` from the normalized `pun-data` scores and the raw
Wiktextract dump:

```sh
cd clj
clj -M -m build wiktionary
```

The command downloads generated inputs into `clj/data/` when they are missing,
then writes `wiktionary.tsv` at the repository root.

Run the Clojure tests with:

```sh
cd clj
clj -M:test
```
