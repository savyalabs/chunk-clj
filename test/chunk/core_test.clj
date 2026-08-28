(ns chunk.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [chunk.core :as c]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop])
  (:import [java.nio.charset StandardCharsets]))

(deftest short-text-stays-whole
  (is (= ["hello world"] (c/split "hello world" {:chunk-size 100}))))

(deftest diagnostics-are-opt-in-and-describe-chunks
  (let [opts {:chunk-size 15 :overlap 0 :diagnostics true}
        text "Alpha beta.\n\nGamma delta.\n\nEpsilon zeta."
        expected [{:text "Alpha beta."
                   :diagnostics {:separator "\n\n" :depth 0 :length 11
                                 :overflowed? false :oversized-atom? false}}
                  {:text "Gamma delta."
                   :diagnostics {:separator "\n\n" :depth 0 :length 14
                                 :overflowed? false :oversized-atom? false}}
                  {:text "Epsilon zeta."
                   :diagnostics {:separator "\n\n" :depth 0 :length 15
                                 :overflowed? false :oversized-atom? false}}]]
    (is (= expected (c/split text opts)))
    (is (= expected (vec (c/split-seq text opts))))
    (is (= ["Alpha beta." "Gamma delta." "Epsilon zeta."]
           (c/split text (dissoc opts :diagnostics))))))

(deftest diagnostics-identify-depth-overflow-and-oversized-atoms
  (let [recursive (c/split "one two three four" {:chunk-size 7 :overlap 0
                                                   :diagnostics true})
        deeper (c/split "aaaaaa b" {:chunk-size 3 :overlap 0
                                     :separators [" " ""] :diagnostics true})
        atom (c/split "supercalifragilistic" {:chunk-size 5 :overlap 0
                                               :separators ["\n\n" "\n" " "]
                                               :diagnostics true})]
    (is (= [{:text "one two"
            :diagnostics {:separator " " :depth 0 :length 7
                           :overflowed? false :oversized-atom? false}}
            {:text "three"
            :diagnostics {:separator " " :depth 0 :length 6
                           :overflowed? false :oversized-atom? false}}
            {:text "four"
            :diagnostics {:separator " " :depth 0 :length 5
                           :overflowed? false :oversized-atom? false}}]
           recursive))
    (is (some #(= {:separator "" :depth 1 :length 3
                   :overflowed? false :oversized-atom? false}
                  (:diagnostics %))
              deeper))
    (is (= [{:text "supercalifragilistic"
             :diagnostics {:separator " " :depth 0 :length 20
                           :overflowed? true :oversized-atom? true}}]
           atom))))

(deftest diagnostics-do-not-add-length-fn-calls
  (let [calls (atom 0)
        length-fn (fn [s] (swap! calls inc) (count s))
        opts {:chunk-size 15 :overlap 5 :length-fn length-fn}
        plain (c/split "one two three four five six seven eight nine ten eleven twelve"
                       opts)
        plain-calls @calls]
    (reset! calls 0)
    (is (= (mapv :text (c/split "one two three four five six seven eight nine ten eleven twelve"
                                (assoc opts :diagnostics true)))
           plain))
    (is (= plain-calls @calls))))

(deftest diagnostics-propagate-through-offsets-and-documents
  (let [opts {:chunk-size 8 :overlap 0 :diagnostics true}
        text "alpha beta gamma"
        chunks (c/split-with-offsets text opts)
        document (c/chunk-document {:id "doc" :text text :metadata {:x 1}} opts)]
    (is (every? #(contains? % :diagnostics) chunks))
    (is (= (mapv :diagnostics chunks) (mapv :diagnostics document)))))

(deftest blank-yields-nothing
  (is (= [] (c/split "" {:chunk-size 100})))
  (is (= [] (c/split "   \n  " {:chunk-size 100})))
  (is (= [] (c/split nil {:chunk-size 100}))))

(deftest respects-chunk-size
  (let [text (str/join " " (repeat 100 "alpha"))         ; ~599 chars
        chunks (c/split text {:chunk-size 50 :overlap 0})]
    (is (> (count chunks) 1))
    (is (every? #(<= (count %) 50) chunks))
    (testing "keeps content without whitespace"
      (is (= (str/replace text #"\s" "")
             (str/replace (apply str chunks) #"\s" ""))))))

(deftest prefers-coarsest-boundary
  ;; Each paragraph fits within the size. The splitter uses \n\n and stops.
  (let [text "Alpha beta.\n\nGamma delta.\n\nEpsilon zeta."
        chunks (c/split text {:chunk-size 15 :overlap 0})]
    (is (= ["Alpha beta." "Gamma delta." "Epsilon zeta."] chunks))))

(deftest recurses-to-finer-separators
  ;; One long line has no paragraph breaks. The splitter must use spaces.
  (let [text (str/join " " (map #(str "w" %) (range 40)))
        chunks (c/split text {:chunk-size 25 :overlap 0})]
    (is (> (count chunks) 1))
    (is (every? #(<= (count %) 25) chunks))))

(deftest overlap-carries-tail-into-next
  (let [text (str/join " " (map str (range 30)))
        chunks (c/split text {:chunk-size 20 :overlap 10})]
    (is (> (count chunks) 1))
    (testing "adjacent chunks have shared boundary words"
      (is (every? (fn [[a b]]
                    (let [tail (set (take-last 3 (str/split a #"\s+")))
                          head (take 3 (str/split b #"\s+"))]
                      (some tail head)))
                  (partition 2 1 chunks))))))

(deftest pluggable-length-fn-counts-tokens-not-chars
  ;; Measure the size in whitespace tokens. This is a substitute for a real tokenizer.
  (let [wc (fn [s] (if (str/blank? s) 0 (count (str/split (str/trim s) #"\s+"))))
        text "one two three four five six seven eight nine ten"
        chunks (c/split text {:chunk-size 3 :overlap 0 :length-fn wc})]
    (is (every? #(<= (wc %) 3) chunks))
    (is (= "one two three" (first chunks)))))

(deftest measures-joined-candidates-with-length-fn
  (let [token-count #(get {"a" 1 "b" 1 "c" 1 " " 0 "a b" 3} % (count %))
        chunks (c/split "a b c" {:chunk-size 2
                                  :overlap 0
                                  :separators [" "]
                                  :length-fn token-count})]
    (is (every? #(<= (token-count %) 2) chunks))))

(deftest oversized-atom-emitted-whole
  ;; No separator can split it below the size. The splitter keeps it whole.
  (let [chunks (c/split "supercalifragilistic" {:chunk-size 5 :overlap 0
                                                :separators ["\n\n" "\n" " "]})]
    (is (= ["supercalifragilistic"] chunks))))

(deftest character-fallback-keeps-astral-code-points-intact
  ;; `𝔘` occupies two UTF-16 code units. A chunk size of two used to place one
  ;; surrogate next to each ASCII character, creating invalid strings.
  (let [chunks (c/split "a𝔘b" {:chunk-size 2 :overlap 0})]
    (is (= ["a" "𝔘" "b"] chunks))
    (is (every? #(= % (String. (.getBytes % StandardCharsets/UTF_8)
                               StandardCharsets/UTF_8))
                chunks))))

(deftest markdown-language-splits-on-heading-boundaries
  (let [text (str "Intro paragraph with enough text to stand alone.\n"
                  "## First section\n"
                  "Some markdown body text.\n"
                  "```clojure\n"
                  "(defn example [] :ok)\n"
                  "```\n"
                  "### Nested section\n"
                  "More markdown body text.\n"
                  "## Second section\n"
                  "Final markdown body text.")
        chunks (c/split text {:chunk-size 90 :overlap 0 :language :markdown})]
    (is (> (count chunks) 1))
    (is (some #(str/includes? % "First section") chunks))
    (is (some #(str/includes? % "Nested section") chunks))
    (is (some #(str/includes? % "Second section") chunks))
    (is (some #(str/starts-with? % "## First section") chunks))
    (is (some #(str/starts-with? % "### Nested section") chunks))
    (is (some #(str/starts-with? % "## Second section") chunks))))

(deftest python-language-splits-on-def-boundaries
  (let [text (str "def first():\n"
                  "    return 'alpha alpha alpha alpha alpha'\n"
                  "\n"
                  "def second():\n"
                  "    return 'beta beta beta beta beta'")
        chunks (c/split text {:chunk-size 70 :overlap 0 :language :python})]
    (is (= 2 (count chunks)))
    (is (str/includes? (first chunks) "def first():"))
    (is (str/starts-with? (second chunks) "def second():"))))

(deftest clojure-language-splits-on-defn-boundaries
  (let [text (str "(defn first-fn []\n"
                  "  :alpha-alpha-alpha-alpha-alpha)\n"
                  "\n"
                  "(defn second-fn []\n"
                  "  :beta-beta-beta-beta-beta)")
        chunks (c/split text {:chunk-size 70 :overlap 0 :language :clojure})]
    (is (= 2 (count chunks)))
    (is (str/includes? (first chunks) "(defn first-fn"))
    (is (str/starts-with? (second chunks) "(defn second-fn"))))

(deftest unknown-language-throws
  (try
    (c/separators-for :nope)
    (is false "Expected unknown language")
    (catch clojure.lang.ExceptionInfo e
      (is (= :unknown-language (:chunk/error (ex-data e))))
      (is (= :nope (:language (ex-data e))))
      (is (contains? (:known (ex-data e)) :python)))))

(deftest language-and-separators-conflict
  (try
    (c/split "hello world" {:chunk-size 5
                            :language :python
                            :separators [" "]})
    (is false "Expected conflicting options")
    (catch clojure.lang.ExceptionInfo e
      (is (= :conflicting-options (:chunk/error (ex-data e)))))))

(deftest invalid-options-report-the-option
  (doseq [[opts option] [[{:chunk-size 0} :chunk-size]
                         [{:overlap -1} :overlap]
                         [{:separators [" " 42]} :separators]
                         [{:length-fn "count"} :length-fn]]]
    (try
      (c/split "hello world" opts)
      (is false (str "Expected invalid " option))
      (catch clojure.lang.ExceptionInfo e
        (is (= :invalid-option (:chunk/error (ex-data e))))
        (is (= option (:option (ex-data e)))))))
  (try
    (c/split 42 {})
    (is false "Expected invalid text")
    (catch clojure.lang.ExceptionInfo e
      (is (= :invalid-option (:chunk/error (ex-data e))))
      (is (= :text (:option (ex-data e)))))))

(deftest invalid-length-results-report-the-length-function
  (try
    (c/split "hello" {:chunk-size 2 :length-fn (constantly "large")})
    (is false "Expected invalid length result")
    (catch clojure.lang.ExceptionInfo e
      (is (= :invalid-length (:chunk/error (ex-data e))))
      (is (= :length-fn (:option (ex-data e)))))))

(deftest sentence-boundaries-are-opt-in-and-configurable
  (let [text "Dr. Smith arrived. It was late! Really?"
        chunks (c/split text {:chunk-size 20
                              :overlap 0
                              :sentence-boundaries {:abbreviations ["Dr."]}})]
    (is (> (count chunks) 1))
    (is (some #(str/includes? % "Dr. Smith") chunks))
    (is (some #(str/includes? % "It was late!") chunks)))
  (is (instance? java.util.regex.Pattern (c/sentence-separators))))

(deftest language-separators-have-default-tail-and-literal-strings
  (doseq [[language separators] c/language-separators]
    (testing language
      (is (vector? separators))
      (is (= ["\n\n" "\n" " " ""] (subvec separators (- (count separators) 4))))
      (is (every? string? separators)))))

(deftest additional-format-presets-are-available
  (doseq [language [:json :xml :yaml :sql :prose :rst]]
    (testing language
      (let [separators (c/separators-for language)]
        (is (vector? separators))
        (is (= c/default-separators (subvec separators (- (count separators) 4))))
        (is (> (count separators) 4))))))

(deftest additional-format-presets-split-structural-boundaries
  (is (= 2 (count (c/split "{\"a\": 1}\n{\"b\": 2}" {:chunk-size 12 :language :json}))))
  (is (some #(str/starts-with? % "<item")
            (c/split "<item>a</item><item>b</item>" {:chunk-size 15 :language :xml})))
  (is (> (count (c/split "first: one\n---\nsecond: two" {:chunk-size 15 :language :yaml})) 1))
  (is (some #(str/starts-with? % "SELECT")
            (c/split "SELECT * FROM users\nWHERE id = 1" {:chunk-size 20 :language :sql})))
  (is (> (count (c/split "First paragraph.\n\nSecond paragraph." {:chunk-size 20 :language :prose})) 1))
  (is (> (count (c/split "Title\n=====\n\nBody text." {:chunk-size 15 :language :rst})) 1)))

(deftest default-behavior-matches-explicit-default-separators
  (let [text "Alpha beta.\n\nGamma delta.\n\nEpsilon zeta."]
    (is (= (c/split text {:chunk-size 15 :overlap 0})
           (c/split text {:chunk-size 15 :overlap 0
                          :separators c/default-separators})))))

(deftest keep-separator-modes
  (let [text "alpha::beta::gamma"
        opts {:chunk-size 10 :overlap 0 :separators ["::" ""]}]
    (is (= ["alpha" "::beta" "::gamma"]
           (c/split text opts)))
    (is (= ["alpha::" "beta::" "gamma"]
           (c/split text (assoc opts :keep-separator :end))))
    (is (= ["alpha" "beta" "gamma"]
           (c/split text (assoc opts :keep-separator false))))))

(deftest regex-separators-respect-retention-modes
  (let [opts {:chunk-size 8
              :overlap 0
              :separators [#"\d+" ""]}
        text "alpha12beta345gamma"]
    (is (= ["alpha" "12beta" "345gamma"] (c/split text opts)))
    (is (= ["alpha12" "beta345" "gamma"]
           (c/split text (assoc opts :keep-separator :end))))
    (is (= ["alpha" "beta" "gamma"]
           (c/split text (assoc opts :keep-separator false))))))

(deftest regex-separators-support-zero-width-matches
  (is (= ["a" "b" "c"]
         (c/split "abc" {:chunk-size 1
                          :separators [#"(?<=.)" ""]
                          :overlap 0}))))

(deftest split-with-offsets-preserves-source-substrings
  (let [source "alpha beta gamma delta epsilon zeta"
        chunks (c/split-with-offsets source {:chunk-size 12 :overlap 3})]
    (is (> (count chunks) 1))
    (is (every? (fn [{:keys [text start end]}]
                  (= text (subs source start end)))
                chunks))))

(deftest split-with-offsets-supports-language-presets
  (let [text "def first():\n    return 1\n\ndef second():\n    return 2"
        chunks (c/split-with-offsets text {:chunk-size 30 :overlap 5 :language :python})]
    (is (every? #(= (:text %) (subs text (:start %) (:end %))) chunks))
    (is (some #(str/starts-with? (:text %) "def second():") chunks))))

(deftest chunk-document-preserves-metadata-and-source-provenance
  (let [source-text (str "Alpha paragraph with enough words.\n\n"
                         "Beta paragraph carries the citation.\n\n"
                         "Gamma paragraph closes the document.")
        metadata {:source "notes.md" :tags #{:rag :example}}
        document {:id "doc-42" :text source-text :metadata metadata}
        chunks (c/chunk-document document {:chunk-size 25 :overlap 0})]
    (is (> (count chunks) 1))
    (is (= (range (count chunks)) (map :index chunks)))
    (is (every? #(= "doc-42" (:id %)) chunks))
    (is (every? #(identical? metadata (:metadata %)) chunks))
    (doseq [chunk chunks]
      (is (= (:text chunk)
             (subs source-text (:start chunk) (:end chunk)))))))

(deftest caches-length-fn-measurements-within-a-split
  (let [calls (atom 0)
        length-fn (fn [s] (swap! calls inc) (count s))
        text "one two three four five six seven eight nine ten eleven twelve"
        chunks (c/split text {:chunk-size 15 :overlap 5 :length-fn length-fn})]
    ;; The implementation without a cache makes 52 calls for this input. With the cache, it makes 29.
    (is (= ["one two three" "four five six" "six seven" "eight nine ten"
            "ten eleven" "twelve"] chunks))
    (is (< @calls 35))))

(deftest streaming-split-is-lazy-and-incremental
  (let [streaming c/split-seq
        calls (atom 0)
        text (str/join " " (map #(str "word-" %) (range 80)))
        opts {:chunk-size 24
              :overlap 5
              :length-fn (fn [s] (swap! calls inc) (count s))}
        chunks (when streaming (streaming text opts))]
    (is streaming "chunk.core/split-seq should be public")
    (is (instance? clojure.lang.LazySeq chunks))
    (is (string? (first chunks)))
    (let [prefix-calls @calls]
      (is (< prefix-calls (count (c/split text (assoc opts :length-fn count))))))))

(deftest streaming-boundaries-match-eager-path-property
  (let [fragments ["alpha" "beta" "gamma" "delta" "😀" "é" "𝔘"]
        separators [["\n\n" "\n" " " ""]
                    ["|" " "]
                    ["::" ""]]
        keep-modes [:start :end false]]
    (doseq [n (range 1 36)
            sep-index (range (count separators))
            keep keep-modes
            overlap (range 0 8)]
      (let [text (str/join (if (zero? (mod n 3)) "\n\n" " ")
                           (take n (cycle fragments)))
            seps (nth separators sep-index)
            opts {:chunk-size (+ 4 (mod n 17))
                  :overlap overlap
                  :separators seps
                  :keep-separator keep
                  :length-fn #(count (str/replace % #"[aeiou]" ""))}]
        (is (= (c/split text opts)
               (vec (c/split-seq text opts)))
            (str "n=" n ", separators=" seps ", keep=" keep
                 ", overlap=" overlap))))))

(deftest streaming-document-preserves-metadata-and-offsets
  (let [metadata {:source "stream.md"}
        document {:id "doc-stream"
                  :text "alpha 😀 beta\n\ngamma é delta"
                  :metadata metadata}
        opts {:chunk-size 10 :overlap 2}
        eager (c/chunk-document document opts)
        streaming (vec (c/chunk-document-seq document opts))]
    (is (= eager streaming))
    (is (every? #(identical? metadata (:metadata %)) streaming))
    (is (every? #(= (:text %) (subs (:text document) (:start %) (:end %)))
                streaming))))

(defspec generated-inputs-have-equal-eager-and-lazy-results 100
  (prop/for-all [fragments (gen/vector (gen/elements ["a" "b" "é" "😀" "𝔘"]) 1 18)
                 separator (gen/elements [" " "|" "::" "\n"])
                 chunk-size (gen/choose 1 24)
                 overlap (gen/choose 0 8)
                 keep (gen/elements [:start :end false])]
      (let [text (str/join separator fragments)
          opts {:chunk-size chunk-size :overlap overlap
                :separators [separator ""] :keep-separator keep}]
      (and (= (c/split text opts) (vec (c/split-seq text opts)))
           (= (c/split text (assoc opts :diagnostics true))
              (vec (c/split-seq text (assoc opts :diagnostics true))))))))
