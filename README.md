# chunk-clj

[![Clojars Project](https://img.shields.io/clojars/v/net.clojars.savya/chunk-clj.svg)](https://clojars.org/net.clojars.savya/chunk-clj)
[![cljdoc](https://cljdoc.org/badge/net.clojars.savya/chunk-clj)](https://cljdoc.org/d/net.clojars.savya/chunk-clj)
[![test](https://github.com/jsavyasachi/chunk-clj/actions/workflows/test.yml/badge.svg)](https://github.com/jsavyasachi/chunk-clj/actions/workflows/test.yml)

This library splits text recursively into chunks for RAG and LLM pipelines. It splits
text on natural boundaries into overlapping chunks by characters **or tokens**.

## Stack

<a href="https://clojure.org"><img src="https://img.shields.io/badge/Clojure-5881D8?style=flat&logo=clojure&logoColor=white" alt="Clojure" /></a>
<a href="https://clojure.org/guides/deps_and_cli"><img src="https://img.shields.io/badge/deps.edn-5881D8?style=flat&logo=clojure&logoColor=fff" alt="deps.edn" /></a>
<a href="https://clojure.github.io/tools.build/"><img src="https://img.shields.io/badge/tools.build-5881D8?style=flat&logo=clojure&logoColor=fff" alt="tools.build" /></a>

To prepare documents for retrieval, split them into chunks that fit a model's context.
Split on suitable boundaries and use a small overlap. With overlap, text that crosses
a chunk boundary stays in both chunks. `chunk-clj` is the Clojure equivalent of
LangChain's `RecursiveCharacterTextSplitter`. It tries a list of separators, from
coarsest (paragraph) to finest (character), until each chunk fits. Then it packs the
chunks and adds the overlap.

You set the size limit. `:length-fn` defaults to characters. Models limit you by
**tokens**, so pass a token counter and chunk by a real token budget.

## Install

tools.deps (`deps.edn`):

```clojure
net.clojars.savya/chunk-clj {:mvn/version "0.6.0"}
```

Leiningen (`project.clj`):

```clojure
[net.clojars.savya/chunk-clj "0.6.0"]
```

## Development

```shell
clojure -M:test
clojure -T:build jar
clojure -T:build deploy
```

## Usage

```clojure
(require '[chunk.core :as chunk])

;; Character-sized chunks with overlap (default):
(chunk/split long-text {:chunk-size 1000 :overlap 200})
;=> ["first ~1000-char chunk ..." "next chunk, sharing ~200 chars ..." ...]

;; Short text is returned whole:
(chunk/split "hello world" {:chunk-size 100})
;=> ["hello world"]

;; Custom separators (e.g. split markdown on headings first):
(chunk/split doc {:chunk-size 800 :separators ["\n## " "\n\n" "\n" " " ""]})

;; Or use a built-in language preset:
(chunk/split doc {:chunk-size 800 :language :markdown})

;; Opt into sentence-aware boundaries (without changing the default hierarchy):
(chunk/split prose {:chunk-size 800
                    :sentence-boundaries {:abbreviations ["Dr." "e.g."]}})

;; Keep source locations for indexing or highlighting:
(chunk/split-with-offsets doc {:chunk-size 800 :language :markdown})
;=> [{:text "...", :start 0, :end 42} ...]

;; Carry document identity and caller metadata through chunking:
(chunk/chunk-document {:id "doc-42"
                       :text doc
                       :metadata {:source "notes.md"}}
                      {:chunk-size 800 :language :markdown})
;=> [{:id "doc-42", :index 0, :start 0, :end 42,
;     :text "...", :metadata {:source "notes.md"}} ...]

;; Stream chunks without collecting the complete result vector:
(doseq [chunk (chunk/split-seq long-text {:chunk-size 1000 :overlap 200})]
  (send-to-index! chunk))

;; Streaming offsets and document metadata are also available:
(chunk/split-with-offsets-seq doc {:chunk-size 800})
(chunk/chunk-document-seq {:id "doc-42" :text doc :metadata {:source "notes.md"}}
                          {:chunk-size 800})
```

By default, `:keep-separator :start` keeps each separator and attaches it to the piece
that follows it. This keeps language and markdown content such as `def ` and `## `.
Use `:keep-separator :end` to attach a separator to the piece before it. Use
`:keep-separator false` for the separator-dropping behavior from 0.2.2.
Separators may also be compiled `java.util.regex.Pattern` values. Regex matches use
the same `:start`, `:end`, and `false` retention modes; zero-width matches are handled
left-to-right without looping.

### Language presets

`:language` selects a separator preset from `chunk.core/language-separators`.
Chunks then land on structural boundaries: headings, function definitions, and
tags. The preset then falls back to paragraphs, lines, words, and characters.
The presets are `:markdown`, `:python`, `:clojure`, `:javascript`,
`:typescript`, `:java`, `:go`, `:rust`, `:html`, `:latex`, `:json`, `:xml`,
`:yaml`, `:sql`, `:prose`, and `:rst`.

```clojure
(chunk/split source {:chunk-size 512 :language :clojure})

(chunk/separators-for :python)
;=> ["\nclass " "\ndef " "\n\tdef " "\n\n" "\n" " " ""]
```

All presets are literal strings, not regexes. Each preset ends with the default
paragraph, line, word, and character tail. If you pass both `:language` and
`:separators`, the function throws. An unknown language keyword also throws,
with the known set in `ex-data`.

### Chunk by tokens

Models limit input by tokens, not characters. Use a real tokenizer to size the chunks:

```clojure
(require '[chunk.core :as chunk]
         '[tokenizers.core :as tok])

(with-open [t (tok/from-pretrained "bert-base-uncased")]
  (chunk/split long-text {:chunk-size 256          ; 256 tokens, not chars
                          :overlap    32
                          :length-fn  #(tok/count-tokens t %)}))
```

Any `String -> number` function works as `:length-fn`. You can target an embedding
model's exact token limit.

## Options

| Key | Default | Meaning |
|-----|---------|---------|
| `:chunk-size` | `1000` | Maximum chunk size, in `:length-fn` units |
| `:overlap` | `0` | Trailing context repeated at the start of the next chunk |
| `:separators` | `["\n\n" "\n" " " ""]` | Ordered literal strings or compiled regex boundaries, coarsest first |
| `:keep-separator` | `:start` | Keep separators on the following piece (`:end` attaches them to the preceding piece; `false` drops them) |
| `:language` | - | Select a built-in language separator preset |
| `:length-fn` | `count` | Measures a string's size (use a token counter) |

Set `:sentence-boundaries` to `true` or to a map with `:terminators` and
`:abbreviations` to add sentence boundaries ahead of the default hierarchy.

`:chunk-size` must be a positive integer, `:overlap` a non-negative integer, and
`:length-fn` must return a non-negative integer. Invalid options throw `ex-info` with
`:chunk/error :invalid-option` and the offending `:option` in `ex-data`. Non-string
text is rejected (with `nil` retained as the existing blank-input shorthand).

If an "atom" is longer than `:chunk-size` and has no finer separator available, the
splitter emits it whole. It does not drop it. An example is one very long word when
`""` is not in `:separators`.

`split-with-offsets` has the same options. It returns `{:text s :start i :end j}` maps,
where the offsets index the original input. With `:keep-separator false`, a chunk that
is not an exact source substring has nil offsets. The splitter caches token-mode
measurements for each split call. It does not tokenize a joined candidate again after
it measures the candidate.

`chunk-document` accepts a document map with `:id`, `:text`, and `:metadata`, and returns
the same source offsets together with the document id, caller metadata, and a zero-based
`:index` for each chunk. It accepts the same options as `split` and
`split-with-offsets`; metadata is passed through unchanged.

`split-seq`, `split-with-offsets-seq`, and `chunk-document-seq` are lazy streaming
counterparts to the eager APIs. They retain only the active packing buffer and overlap
tail while producing chunks, so consumers can process large documents incrementally.
Their chunk boundaries, grapheme-safe character fallback, overlap, separator behavior,
offsets, and document metadata are identical to the corresponding eager APIs.

## License

Copyright © 2026 Savyasachi

Distributed under the [Eclipse Public License 2.0](https://www.eclipse.org/legal/epl-2.0/).
