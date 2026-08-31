(ns chunk.core
  "This namespace splits text recursively into chunks for RAG and LLM pipelines.

  `split` breaks text into overlapping chunks. No chunk is larger than a target size.
  `split` tries an ordered list of separators, from coarsest (paragraph) to finest
  (character), so chunks use natural boundaries. `:length-fn` measures the size. Its
  default is `count`, which measures characters. Pass a token counter, for example
  tokenizers-clj's `count-tokens`, to chunk by tokens. Tokens are the correct unit for
  a model with a token limit."
  (:require [clojure.string :as str])
  (:import [java.util.regex Pattern]))

(def default-separators
  "Split boundaries from coarsest to finest. The empty string splits into characters."
  ["\n\n" "\n" " " ""])

(def ^:private default-keep-separator :default-start)

(defn sentence-separators
  "Return a regex separator for sentence boundaries.

  `config` may contain `:terminators` and `:abbreviations` collections."
  ([] (sentence-separators nil))
  ([{:keys [terminators abbreviations]}]
   (let [terminators (or terminators ["." "!" "?" "。" "！" "？"])
         abbreviations (or abbreviations [])
         escaped (fn [s] (Pattern/quote s))
         negative (apply str (map #(str "(?<!" (escaped %) ")") abbreviations))
         ending (apply str (interpose "|" (map escaped terminators)))]
     (re-pattern (str negative "(?<=" ending ")\\s+")))))

(def language-separators
  "Split boundaries for each language, with the coarsest first."
  {:markdown (into ["\n# " "\n## " "\n### " "\n#### " "\n##### " "\n###### "
                   "```\n"
                   "\n\n***\n\n" "\n\n---\n\n" "\n\n___\n\n"]
                  default-separators)
   :python (into ["\nclass " "\ndef " "\n\tdef "] default-separators)
   :clojure (into ["\n(defn " "\n(def " "\n(defmacro " "\n(defmulti "
                   "\n(defmethod " "\n(defprotocol " "\n(defrecord "
                   "\n(deftest " "\n(ns "]
                  default-separators)
   :javascript (into ["\nfunction " "\nconst " "\nlet " "\nvar " "\nclass "
                      "\nif " "\nfor " "\nwhile " "\nswitch " "\ncase "
                      "\ndefault "]
                     default-separators)
   :typescript (into ["\nfunction " "\nconst " "\nlet " "\nvar "
                      "\ninterface " "\nenum " "\ntype " "\nnamespace "
                      "\nclass " "\nif " "\nfor " "\nwhile " "\nswitch "
                      "\ncase " "\ndefault "]
                     default-separators)
   :java (into ["\nclass " "\npublic " "\nprotected " "\nprivate " "\nstatic "
                "\nif " "\nfor " "\nwhile " "\nswitch " "\ncase "]
               default-separators)
   :go (into ["\nfunc " "\nvar " "\nconst " "\ntype " "\nif " "\nfor "
              "\nswitch " "\ncase "]
             default-separators)
   :rust (into ["\nfn " "\nconst " "\nlet " "\nif " "\nwhile " "\nfor "
                "\nloop " "\nmatch "]
               default-separators)
   :html (into ["<body" "<div" "<p" "<br" "<li" "<h1" "<h2" "<h3" "<h4"
                "<h5" "<h6" "<span" "<table" "<tr" "<td" "<th" "<ul" "<ol"
                "<header" "<footer" "<nav" "<head" "<style" "<script"
                "<meta" "<title"]
               default-separators)
   :latex (into ["\n\\chapter{" "\n\\section{" "\n\\subsection{"
                 "\n\\subsubsection{" "\n\\begin{enumerate}"
                 "\n\\begin{itemize}" "\n\\begin{description}"
                 "\n\\begin{list}" "\n\\begin{quote}" "\n\\begin{quotation}"
                 "\n\\begin{verse}" "\n\\begin{verbatim}" "\n\\begin{align}"]
                default-separators)
   :json (into ["\n{" "\n[" "}," ","] default-separators)
   :xml (into ["<item" "</item>" "<section" "<record" "<entry"] default-separators)
   :yaml (into ["\n---\n" "\n...\n" "\n- " "\n"] default-separators)
   :sql (into ["\nSELECT " "\nWITH " "\nINSERT " "\nUPDATE " "\nDELETE "
                "\nFROM " "\nWHERE " "\nGROUP BY " "\nORDER BY "]
              default-separators)
   :prose (into ["\n\n" "? " ". " "! "] default-separators)
   :rst (into ["\n=====\n" "\n-----\n" "\n^^^^^\n" "\n~~~~~\n" "\n.. "]
               default-separators)})

(defn separators-for
  "Return the separator vector for language keyword `lang`."
  [lang]
  (if-let [separators (get language-separators lang)]
    separators
    (throw (ex-info "Unknown language"
                    {:chunk/error :unknown-language
                     :language lang
                     :known (set (keys language-separators))}))))

(declare regex-separator?)

(defn- invalid-option! [option value message]
  (throw (ex-info message {:chunk/error :invalid-option
                           :option option
                           :value value})))

(defn- validate-options! [text chunk-size overlap separators length-fn keep-separator]
  (when (and (some? text) (not (string? text)))
    (invalid-option! :text text "Text must be a string or nil"))
  (when-not (and (integer? chunk-size) (pos? chunk-size))
    (invalid-option! :chunk-size chunk-size "Chunk size must be a positive integer"))
  (when-not (and (integer? overlap) (<= 0 overlap))
    (invalid-option! :overlap overlap "Overlap must be a non-negative integer"))
  (when-not (and (sequential? separators) (seq separators)
                 (every? #(or (string? %) (regex-separator? %)) separators))
    (invalid-option! :separators separators "Separators must be a non-empty collection of strings or regex patterns"))
  (when-not (ifn? length-fn)
    (invalid-option! :length-fn length-fn "Length function must be callable"))
  (when-not (contains? #{:start :end false} keep-separator)
    (invalid-option! :keep-separator keep-separator "Keep separator must be :start, :end, or false")))

(defn- measured-length [length-fn value]
  (let [result (length-fn value)]
    (if (and (integer? result) (not (neg? result)))
      (long result)
      (throw (ex-info "Length function must return a non-negative integer"
                      {:chunk/error :invalid-length
                       :option :length-fn
                       :value result
                       :text value})))))

(defn- regex-separator? [sep]
  (instance? Pattern sep))

(defn- separator-present? [^String text sep]
  (if (regex-separator? sep)
    (.find (.matcher ^Pattern sep text))
    (str/includes? text sep)))

(defn- split-on-regex [^String s ^Pattern sep keep-separator]
  (let [matcher (.matcher sep s)]
    (loop [piece-start 0, pieces []]
      (if (.find matcher)
        (let [at (.start matcher)
              end (.end matcher)]
          (cond
            (= keep-separator false)
            (recur end (cond-> pieces (< piece-start at) (conj (subs s piece-start at))))
            (or (= keep-separator :start)
                (= keep-separator default-keep-separator))
            (recur at (cond-> pieces (< piece-start at) (conj (subs s piece-start at))))
            :else
            (recur end (conj pieces (subs s piece-start end)))))
        (cond-> pieces (< piece-start (count s)) (conj (subs s piece-start)))))))

(defn- split-on
  "Split s on a literal string or regex separator. Attach it to an adjacent piece
  when requested. Regex matches are processed in matcher order, including zero-width
  matches."
  [^String s sep keep-separator]
  (cond
    (= sep "") (vec (re-seq (Pattern/compile "\\X") s))
    (regex-separator? sep) (split-on-regex s sep keep-separator)
    (= keep-separator false) (->> (str/split s (re-pattern (Pattern/quote sep)) -1)
                                  (filterv (complement #(= "" %))))
    :else
    (let [separator-length (count sep)]
      (loop [piece-start 0, search-start 0, pieces []]
        (let [at (.indexOf s sep search-start)]
          (if (neg? at)
            (cond-> pieces
              (< piece-start (count s)) (conj (subs s piece-start)))
            (if (or (= keep-separator :start)
                    (= keep-separator default-keep-separator))
              (recur at (+ at separator-length) (cond-> pieces
                                                  (< piece-start at)
                                                  (conj (subs s piece-start at))))
              (recur (+ at separator-length) (+ at separator-length)
                     (conj pieces (subs s piece-start (+ at separator-length)))))))))))

(defn- join-separator [sep keep-separator]
  (if (or (not= keep-separator false) (regex-separator? sep)) "" sep))

(defn- join-trim [pieces sep keep-separator]
  (let [d (str/join sep pieces)]
    (when-not (str/blank? d)
      (if (or (= keep-separator false)
              (= keep-separator default-keep-separator))
        (str/trim d)
        d))))

(def ^:private max-length-cache-entries 256)

(defn- cache-length! [cache value length]
  (swap! cache
         (fn [entries]
           (let [entries (assoc entries value length)]
             (if (> (count entries) max-length-cache-entries)
               (dissoc entries (first (keys entries)))
               entries)))))

(defn- joined-length [pieces sep length-fn cache]
  (let [joined (str/join sep pieces)]
    (if (contains? @cache joined)
      (get @cache joined)
      (let [length (measured-length length-fn joined)]
        (cache-length! cache joined length)
        length))))

(defn- cached-length [value length-fn cache]
  (if (contains? @cache value)
    (get @cache value)
    (let [length (measured-length length-fn value)]
      (cache-length! cache value length)
      length)))

(defn- chunk-record [text separator depth length chunk-size oversized-atom?]
  {:text text
   :separator separator
   :depth depth
   :length length
   :overflowed? (> length (long chunk-size))
   :oversized-atom? oversized-atom?})

(defn- render-chunk [chunk diagnostics?]
  (if diagnostics?
    {:text (:text chunk)
     :diagnostics (select-keys chunk [:separator :depth :length
                                      :overflowed? :oversized-atom?])}
    (:text chunk)))

(defn- trim-overlap
  "Remove pieces from the front of the current buffer until it is within the overlap
  budget and can contain the next piece."
  [cur next-piece sep chunk-size overlap length-fn cache]
  (loop [cur cur, cur-len (joined-length cur sep length-fn cache)]
    (if (and (seq cur)
             (or (> cur-len (long overlap))
                 (> (joined-length (conj cur next-piece) sep length-fn cache)
                    (long chunk-size))))
      (let [cur (subvec cur 1)]
        (recur cur (joined-length cur sep length-fn cache)))
      cur)))

(defn- merge-splits
  "Pack pieces, each already <= chunk-size, into chunks of <= chunk-size. Join pieces
  with sep. Carry trailing pieces of `overlap` size into the next chunk."
  [pieces sep selected-separator depth chunk-size overlap length-fn keep-separator cache]
  (loop [pieces (seq pieces), cur [], out []]
    (if-let [d (first pieces)]
      (let [candidate (conj cur d)
            candidate-len (joined-length candidate sep length-fn cache)]
        (if (and (seq cur) (> candidate-len (long chunk-size)))
          (let [doc (join-trim cur sep keep-separator)
                out (cond-> out doc (conj (chunk-record
                                           doc selected-separator depth
                                           (joined-length cur sep length-fn cache)
                                           chunk-size false)))
                cur (trim-overlap cur d sep chunk-size overlap length-fn cache)]
            (recur pieces cur out))                      ; Retry the same d with the trimmed buffer.
          (recur (next pieces) candidate out)))
      (if-let [doc (join-trim cur sep keep-separator)]
        (conj out (chunk-record doc selected-separator depth
                                (joined-length cur sep length-fn cache)
                                chunk-size false))
        out))))

(defn- recursive-split [text separators depth chunk-size overlap length-fn keep-separator cache]
  (let [sep (or (some #(when (and (not= "" %) (separator-present? text %)) %) separators)
                (last separators))
        deeper-seps (vec (rest (drop-while #(not= % sep) separators)))
        pieces (split-on text sep keep-separator)]
    (loop [pieces (seq pieces), good [], out []]
      (if-let [p (first pieces)]
        (let [p-length (cached-length p length-fn cache)]
          (if (<= p-length
                    chunk-size)
            (recur (next pieces) (conj good p) out)
            (let [join-sep (join-separator sep keep-separator)
                  merged (if (seq good)
                           (merge-splits good join-sep sep depth chunk-size overlap
                                         length-fn keep-separator cache)
                           [])
                  deeper (if (seq deeper-seps)
                           (recursive-split p deeper-seps (inc depth) chunk-size overlap
                                            length-fn keep-separator cache)
                           [(chunk-record p sep depth p-length chunk-size true)])]
              (recur (next pieces) [] (into (into out merged) deeper)))))
        (into out (when (seq good)
                    (merge-splits good (join-separator sep keep-separator) sep depth
                                  chunk-size overlap length-fn keep-separator cache)))))))

(defn- split-on-lazy
  "Lazy counterpart to split-on. It yields the same pieces without collecting them."
  [^String s sep keep-separator]
  (cond
    (= sep "") (re-seq (Pattern/compile "\\X") s)
    (regex-separator? sep) (lazy-seq (split-on-regex s sep keep-separator))
    (= keep-separator false)
    (letfn [(pieces [start]
              (lazy-seq
               (let [at (.indexOf s sep start)]
                 (cond
                   (neg? at) (when (< start (count s)) (list (subs s start)))
                   (= at start) (pieces (+ at (count sep)))
                   :else (cons (subs s start at)
                               (pieces (+ at (count sep))))))))]
      (pieces 0))
    :else
    (letfn [(pieces [piece-start search-start]
              (lazy-seq
               (let [at (.indexOf s sep search-start)
                     separator-length (count sep)]
                 (if (neg? at)
                   (when (< piece-start (count s))
                     (list (subs s piece-start)))
                   (if (or (= keep-separator :start)
                           (= keep-separator default-keep-separator))
                     (cons (when (< piece-start at)
                             (subs s piece-start at))
                           (pieces at (+ at separator-length)))
                     (cons (subs s piece-start (+ at separator-length))
                           (pieces (+ at separator-length)
                                   (+ at separator-length))))))))]
      (if (or (= keep-separator :start)
              (= keep-separator default-keep-separator))
        (filter identity (pieces 0 0))
        (pieces 0 0)))))

(defn- merge-splits-lazy
  "Lazy counterpart to merge-splits. Only the current chunk buffer is retained."
  [pieces sep selected-separator depth chunk-size overlap length-fn keep-separator cache cur]
  (lazy-seq
   (if-let [d (first pieces)]
     (let [candidate (conj cur d)
           candidate-len (joined-length candidate sep length-fn cache)]
       (if (and (seq cur) (> candidate-len (long chunk-size)))
         (let [doc (join-trim cur sep keep-separator)]
           (if doc
            (cons (chunk-record doc selected-separator depth
                                (joined-length cur sep length-fn cache)
                                chunk-size false)
                   (merge-splits-lazy pieces sep selected-separator depth chunk-size overlap length-fn keep-separator cache
                                      (trim-overlap cur d sep chunk-size overlap
                                                     length-fn cache)))
             (merge-splits-lazy pieces sep selected-separator depth chunk-size overlap length-fn keep-separator cache
                                (trim-overlap cur d sep chunk-size overlap
                                               length-fn cache))))
         (merge-splits-lazy (next pieces) sep selected-separator depth chunk-size overlap length-fn keep-separator cache
                            candidate)))
     (when-let [doc (join-trim cur sep keep-separator)]
       (list (chunk-record doc selected-separator depth
                           (joined-length cur sep length-fn cache)
                           chunk-size false))))))

(defn- recursive-split-lazy
  [text separators depth chunk-size overlap length-fn keep-separator cache]
  (let [sep (or (some #(when (and (not= "" %) (separator-present? text %)) %) separators)
                (last separators))
        deeper-seps (vec (rest (drop-while #(not= % sep) separators)))
        pieces (split-on-lazy text sep keep-separator)
        join-sep (join-separator sep keep-separator)]
    (letfn [(walk [pieces good]
              (lazy-seq
               (if-let [p (first pieces)]
                 (let [p-length (cached-length p length-fn cache)]
                   (if (<= p-length chunk-size)
                     (let [candidate (conj good p)]
                       (if (and (seq good)
                                (> (joined-length candidate join-sep length-fn cache)
                                   (long chunk-size)))
                         (if-let [doc (join-trim good join-sep keep-separator)]
                            (cons (chunk-record doc sep depth
                                                (joined-length good join-sep length-fn cache)
                                                chunk-size false)
                                 (walk pieces (trim-overlap good p join-sep
                                                            chunk-size overlap length-fn cache)))
                           (walk pieces (trim-overlap good p join-sep
                                                      chunk-size overlap length-fn cache)))
                         (walk (next pieces) candidate)))
                     (let [deeper (if (seq deeper-seps)
                                    (recursive-split-lazy p deeper-seps (inc depth)
                                                           chunk-size overlap length-fn
                                                           keep-separator cache)
                                    (list (chunk-record p sep depth p-length
                                                         chunk-size true)))]
                       (concat (when-let [doc (when (seq good)
                                               (join-trim good join-sep keep-separator))]
                                 (list (chunk-record doc sep depth
                                                     (joined-length good join-sep length-fn cache)
                                                     chunk-size false)))
                               deeper
                               (walk (next pieces) [])))))
                 (if (seq good)
                   (merge-splits-lazy good join-sep sep depth chunk-size overlap
                                      length-fn keep-separator cache [])
                   ()))))]
      (walk pieces []))))

(defn split-seq
  "Lazily split `text` into chunk strings.

  This is the streaming counterpart to `split`: it produces identical chunks,
  but retains only the active recursive buffer and overlap tail as the result is
  consumed. Options match `split`."
  ([text] (split-seq text nil))
  ([text opts]
   (let [opts (or opts {})
         {:keys [chunk-size overlap separators language length-fn keep-separator diagnostics]
          :or {chunk-size 1000 overlap 0 length-fn count}} opts
         separators (cond
                      (and (contains? opts :language) (contains? opts :separators))
                      (throw (ex-info "Conflicting options"
                                      {:chunk/error :conflicting-options
                                       :options #{:language :separators}}))
                      (contains? opts :language) (separators-for language)
                      (contains? opts :separators) separators
                      :else (if (contains? opts :sentence-boundaries)
                              (into [(sentence-separators (when (map? (:sentence-boundaries opts))
                                                           (:sentence-boundaries opts)))]
                                    default-separators)
                              default-separators))]
     (validate-options! text chunk-size overlap separators length-fn
                        (if (contains? opts :keep-separator) keep-separator :start))
     (if (str/blank? (str text))
       (lazy-seq nil)
       (let [chunks (recursive-split-lazy text (vec separators) 0 chunk-size overlap length-fn
                                          (if (contains? opts :keep-separator)
                                            keep-separator
                                            default-keep-separator)
                                          (atom {}))]
         (map #(render-chunk % diagnostics) chunks))))))

(defn split-with-offsets-seq
  "Lazily split `text` into maps with `:text`, `:start`, and `:end` offsets.

  Options and offset behavior match `split-with-offsets`."
  ([text] (split-with-offsets-seq text nil))
  ([text opts]
   (let [source (str text)]
     (letfn [(offsets [chunks lower-bound]
               (lazy-seq
                (when-let [chunk (first chunks)]
                  (let [chunk-text (if (map? chunk) (:text chunk) chunk)
                        start (.indexOf ^String source ^String chunk-text (int lower-bound))
                        found? (not (neg? start))
                        end (when found? (+ start (count chunk-text)))
                        next-lower-bound (if found? (inc start) lower-bound)]
                    (cons (cond-> {:text chunk-text :start (when found? start) :end end}
                            (map? chunk) (assoc :diagnostics (:diagnostics chunk)))
                          (offsets (next chunks) next-lower-bound))))))]
       (offsets (split-seq source opts) 0)))))

(defn chunk-document-seq
  "Lazily split a document while preserving id, metadata, index, and offsets.

  The document and options match `chunk-document`."
  ([document] (chunk-document-seq document nil))
  ([{:keys [id text metadata]} opts]
   (map-indexed (fn [index chunk]
                  (assoc chunk :id id :index index :metadata metadata))
                (split-with-offsets-seq text opts))))

(defn split
  "Split `text` into a vector of chunk strings.

  Options:
  - `:chunk-size` max size of a chunk, in `:length-fn` units (default 1000)
  - `:overlap`    size of trailing context repeated at the start of the next chunk (default 0)
  - `:separators` ordered split boundaries, coarsest first (default `default-separators`)
  - `:language`   keyword selecting `language-separators`; conflicts with `:separators`
  - `:keep-separator` `:start` (default), `:end`, or `false`; attach separators to the
                      following or preceding piece, or drop them
  - `:length-fn`  measures a string's size; default `count` (characters). Pass a token
                  counter to chunk by tokens.

  A piece with no admissible finer separator is emitted whole. This includes an \"atom\"
  longer than `:chunk-size`."
  ([text] (split text nil))
  ([text opts]
   (let [opts (or opts {})
         {:keys [chunk-size overlap separators language length-fn keep-separator diagnostics]
          :or {chunk-size 1000 overlap 0 length-fn count}} opts
         separators (cond
                      (and (contains? opts :language) (contains? opts :separators))
                      (throw (ex-info "Conflicting options"
                                      {:chunk/error :conflicting-options
                                       :options #{:language :separators}}))

                      (contains? opts :language) (separators-for language)
                      (contains? opts :separators) separators
                      :else (if (contains? opts :sentence-boundaries)
                              (into [(sentence-separators (when (map? (:sentence-boundaries opts))
                                                           (:sentence-boundaries opts)))]
                                    default-separators)
                              default-separators))]
     (validate-options! text chunk-size overlap separators length-fn
                        (if (contains? opts :keep-separator) keep-separator :start))
     (if (str/blank? (str text))
       []
       (mapv #(render-chunk % diagnostics)
             (recursive-split text (vec separators) 0 chunk-size overlap length-fn
                              (if (contains? opts :keep-separator)
                                keep-separator
                                default-keep-separator)
                              (atom {})))))))

(defn split-with-offsets
  "Split `text` into maps with `:text`, `:start`, and `:end` offsets.

  Options match `split`. Offsets are character indices in the original text. With
  `:keep-separator false`, a chunk that is not an exact substring has nil offsets."
  ([text] (split-with-offsets text nil))
  ([text opts]
   (let [source (str text)]
     (loop [chunks (seq (split text opts)), lower-bound 0, out []]
       (if-let [chunk (first chunks)]
         (let [chunk-text (if (map? chunk) (:text chunk) chunk)
               start (.indexOf ^String source ^String chunk-text (int lower-bound))
               found? (not (neg? start))
               end (when found? (+ start (count chunk-text)))
               next-lower-bound (if found? (inc start) lower-bound)]
           (recur (next chunks) next-lower-bound
                  (conj out (cond-> {:text chunk-text :start (when found? start) :end end}
                              (map? chunk) (assoc :diagnostics (:diagnostics chunk))))))
         out)))))

(defn chunk-document
  "Split a document into chunks carrying its id, metadata, and source offsets.

  The document must be a map with `:id`, `:text`, and `:metadata` keys. Options
  match `split`, including `:chunk-size`, `:overlap`, `:separators`, `:language`,
  `:keep-separator`, and `:length-fn`. Each returned map has `:id`, `:index`,
  `:start`, `:end`, `:text`, and `:metadata` keys. Offsets are character indices
  into the document's original `:text` value.

  With `:keep-separator false`, offsets may be nil when a chunk is not an exact
  source substring, matching `split-with-offsets`."
  ([document] (chunk-document document nil))
  ([{:keys [id text metadata]} opts]
   (map-indexed (fn [index chunk]
                  (assoc chunk
                         :id id
                         :index index
                         :metadata metadata))
                (split-with-offsets text opts))))
