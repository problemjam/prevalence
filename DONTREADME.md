# prevalence

## Goals

### Prevalence

> Does the pipeline use corpus frequency?

No. The pipeline uses estimated prevalence. That way, you can use the dataset to be confident you know the phrases most people know.

### Exhaustiveness

> Does the pipeline pull data from Wiktionary?

Yes. `prevalence` processes Wiktionary data and writes the output to `wiktionary.tsv`.

> Does the pipeline pull data from Wikipedia?

Yes. `prevalence` processes Wikipedia data and writes the output to `wikipedia.tsv`.

### Efficiency

> Does `wiktionary.tsv` distinguish between single words and multi-word phrases?

Yes. `wiktionary.tsv` tells you if it's a single word or a multi-word phrase using a boolean column.

Going through single words is about checking if you know what each word means. But sifting through multi-word phrases is about making sure you understand the idiomatic meaning of the phrase. Going over the lists separately can cut down on context switching.

> Does `wiktionary.tsv` show whether an entry has a lemma tag?

Yes. The dataset uses a boolean column to flag entries that have a lemma tag.

If you only look at entries with lemma tags, you'll have fewer entries to sift through.

But `wiktionary.tsv` won't drop entries without a lemma tag, because doing so would be lossy.

> Does the pipeline rely on NLP tools to spot lemmas?

No. Algorithmic lemmatizers sometimes make classification errors.
