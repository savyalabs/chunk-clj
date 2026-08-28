# Changelog

All notable changes to this project are documented here. Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.6.0] - 2026-08-27

### Added

- Regex separators in `:separators`, with the existing separator retention modes.
- Literal language presets for JSON, XML, YAML, SQL, plain prose, and reStructuredText.
- Actionable validation for splitter options and length-function results.
- Generative eager/lazy equivalence coverage for Unicode, separators, overlap, and
  retention modes.
- Opt-in configurable sentence-boundary splitting with abbreviation support.

## [0.5.0] - 2026-08-27

### Added

- Lazy streaming APIs: `split-seq`, `split-with-offsets-seq`, and
  `chunk-document-seq`. They preserve eager chunk boundaries, overlap, grapheme-safe
  splitting, source offsets, and document metadata without materializing the result vector.

## [0.4.0] - 2026-08-27

### Added

- `chunk.core/chunk-document` for chunking document maps while preserving their id,
  metadata, chunk index, and source character offsets.

## [0.3.1] - 2026-08-17

### Fixed

- The character fallback splits on grapheme-cluster boundaries, so
  multi-code-unit characters (emoji, astral-plane code points) are no longer
  split into lone surrogates.

## [0.3.0] - 2026-07-23

### Added
- `:keep-separator` option with `:start`, `:end`, and `false` modes.
- `chunk.core/split-with-offsets` for source character offsets.

### Changed
- Separators are now kept by default, fixing separator loss in language presets compared
  with 0.2.2.
- Redundant `:length-fn` calls for already-measured joined candidates are cached per split.

## [0.2.2] - 2026-07-16

### Fixed
- Measure fully-joined chunk candidates with `:length-fn` so token-budget mode no longer exceeds the budget on non-additive (BPE) tokenizers.

## [0.2.1] - 2026-07-12

### Changed
- Migrate the build to deps.edn and tools.build, with Leiningen supported via lein-tools-deps.

## [0.2.0] - 2026-07-07

### Added
- `chunk.core/language-separators` - separator presets for `:markdown`,
  `:python`, `:clojure`, `:javascript`, `:typescript`, `:java`, `:go`, `:rust`,
  `:html`, and `:latex`, so chunks land on structural boundaries (headings,
  function definitions, tags) before the paragraph/line/word/character tail.
- `chunk.core/separators-for` - preset lookup; unknown language throws with the
  known set in `ex-data`.
- `:language` option on `split` - sugar for `:separators (separators-for lang)`;
  passing both `:language` and `:separators` throws.

## [0.1.1] - 2026-06-26

### Changed
- Relicensed from EPL 1.0 to EPL 2.0 for cross-repo consistency.
- Added tag-triggered Clojars release workflow and standardized badges/community health files.

## [0.1.0] - 2026-06-23

### Added
- Initial release.
- `chunk.core/split` - recursive text splitter for RAG / LLM pipelines. Splits text
  into overlapping chunks no larger than a target size, trying ordered separators
  (paragraph -> line -> word -> character) so chunks land on natural boundaries.
- Options: `:chunk-size`, `:overlap`, `:separators`, and a pluggable `:length-fn`
  (default `count` = characters) so you can chunk by **tokens** instead - e.g. with
  tokenizers-clj's `count-tokens`.
- Oversized atoms with no admissible finer separator are emitted whole, never dropped.
- Zero runtime dependencies.

[0.1.0]: https://github.com/jsavyasachi/chunk-clj/releases/tag/0.1.0
