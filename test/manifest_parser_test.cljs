(ns manifest-parser-test
  (:require ["vitest" :refer [describe test expect]]
            [manifest-parser :as mp]))

;; ============================================================
;; manifest-parser tests
;; ============================================================

(defn- test-extracts-manifest-from-metadata-map []
  (let [code "^{:epupp/script-name \"test.cljs\"\n  :epupp/auto-run-match \"https://example.com/*\"\n  :epupp/description \"desc\"\n  :epupp/run-at \"document-start\"}\n(ns test)"
        manifest (mp/extract-manifest code)]
    ;; script-name is normalized (test.cljs stays test.cljs)
    (-> (expect (:script-name manifest))
        (.toBe "test.cljs"))
    (-> (expect (:auto-run-match manifest))
        (.toBe "https://example.com/*"))
    (-> (expect (:description manifest))
        (.toBe "desc"))
    (-> (expect (:run-at manifest))
        (.toBe "document-start"))))

(defn- test-defaults-run-at-when-omitted []
  (let [code "^{:epupp/script-name \"test.cljs\"}\n(ns test)"
        manifest (mp/extract-manifest code)]
    (-> (expect (:run-at manifest))
        (.toBe "document-idle"))
    (-> (expect (mp/get-run-at code))
        (.toBe "document-idle"))))

(defn- test-defaults-invalid-run-at-instead-of-throwing []
  (let [code "^{:epupp/script-name \"test.cljs\"\n  :epupp/run-at \"nope\"}\n(ns test)"
        manifest (mp/extract-manifest code)]
    ;; Invalid run-at should default instead of throwing
    (-> (expect (:run-at manifest))
        (.toBe "document-idle"))
    (-> (expect (:run-at-invalid? manifest))
        (.toBe true))
    (-> (expect (:raw-run-at manifest))
        (.toBe "nope"))))

(defn- test-returns-nil-for-code-without-manifest []
  (let [code "(defn foo [] 42)"
        manifest (mp/extract-manifest code)]
    ;; Clojure nil becomes JS undefined (no epupp keys found)
    (-> (expect manifest)
        (.toBeUndefined))))

(defn- test-has-manifest-detects-presence []
  (-> (expect (mp/has-manifest? "^{:epupp/script-name \"x.cljs\"} (ns x)"))
      (.toBe true))
  (-> (expect (mp/has-manifest? "(ns x)"))
      (.toBe false)))

(defn- test-allows-whitespace-before-manifest []
  (let [code "   \n\n^{:epupp/script-name \"test.cljs\"}\n(ns test)"
        manifest (mp/extract-manifest code)]
    ;; Already normalized name stays as-is
    (-> (expect (:script-name manifest))
        (.toBe "test.cljs"))))

(defn- test-allows-line-comments-before-manifest []
  (let [code ";; My awesome script\n;; Does cool things\n\n^{:epupp/script-name \"test.cljs\"}\n(ns test)"
        manifest (mp/extract-manifest code)]
    (-> (expect (:script-name manifest))
        (.toBe "test.cljs"))))

(describe "manifest-parser"
          (fn []
            (test "extracts manifest from metadata map" test-extracts-manifest-from-metadata-map)
            (test "defaults run-at when omitted" test-defaults-run-at-when-omitted)
            (test "defaults invalid run-at instead of throwing" test-defaults-invalid-run-at-instead-of-throwing)
            (test "returns nil for code without manifest" test-returns-nil-for-code-without-manifest)
            (test "has-manifest? detects presence" test-has-manifest-detects-presence)
            (test "allows whitespace before manifest" test-allows-whitespace-before-manifest)
            (test "allows line comments before manifest" test-allows-line-comments-before-manifest)))

;; ============================================================
;; manifest-parser enhanced features tests
;; ============================================================

(defn- test-parses-auto-run-match-as-vector-of-strings []
  (let [code "^{:epupp/script-name \"test.cljs\"
  :epupp/auto-run-match [\"https://github.com/*\" \"https://gist.github.com/*\"]}
(ns test)"
        manifest (mp/extract-manifest code)]
    ;; auto-run-match should be preserved as vector
    (-> (expect (:auto-run-match manifest))
        (.toEqual ["https://github.com/*" "https://gist.github.com/*"]))))

(defn- test-parses-auto-run-match-as-single-string []
  (let [code "^{:epupp/script-name \"test.cljs\"
  :epupp/auto-run-match \"https://github.com/*\"}
(ns test)"
        manifest (mp/extract-manifest code)]
    ;; Single string should be preserved as-is
    (-> (expect (:auto-run-match manifest))
        (.toBe "https://github.com/*"))))

(defn- test-collects-all-epupp-keys-found []
  (let [code "^{:epupp/script-name \"test.cljs\"
  :epupp/auto-run-match \"https://example.com/*\"
  :epupp/description \"A test script\"
  :epupp/run-at \"document-start\"
  :epupp/unknown-key \"should be noted\"}
(ns test)"
        manifest (mp/extract-manifest code)]
    ;; Should have found all epupp keys
    (-> (expect (:found-keys manifest))
        (.toContain "epupp/script-name"))
    (-> (expect (:found-keys manifest))
        (.toContain "epupp/unknown-key"))))

(defn- test-identifies-unknown-epupp-keys []
  (let [code "^{:epupp/script-name \"test.cljs\"
  :epupp/auto-run-match \"https://example.com/*\"
  :epupp/author \"PEZ\"
  :epupp/version \"1.0\"}
(ns test)"
        manifest (mp/extract-manifest code)]
    (-> (expect (:unknown-keys manifest))
        (.toContain "epupp/author"))
    (-> (expect (:unknown-keys manifest))
        (.toContain "epupp/version"))
    ;; Known keys should NOT be in unknown
    (-> (expect (:unknown-keys manifest))
        (.not.toContain "epupp/script-name"))
    (-> (expect (:unknown-keys manifest))
        (.not.toContain "epupp/auto-run-match"))))

(defn- test-returns-raw-script-name-for-hint-display []
  (let [code "^{:epupp/script-name \"GitHub Tweaks\"}
(ns test)"
        manifest (mp/extract-manifest code)]
    ;; raw-script-name preserves original input
    (-> (expect (:raw-script-name manifest))
        (.toBe "GitHub Tweaks"))
    ;; script-name is normalized
    (-> (expect (:script-name manifest))
        (.toBe "github_tweaks.cljs"))))

(defn- test-applies-name-normalization []
  (let [code "^{:epupp/script-name \"My Cool Script\"}
(ns test)"
        manifest (mp/extract-manifest code)]
    (-> (expect (:script-name manifest))
        (.toBe "my_cool_script.cljs"))))

(defn- test-detects-name-was-normalized-different-from-raw []
  (let [code "^{:epupp/script-name \"GitHub Tweaks\"}
(ns test)"
        manifest (mp/extract-manifest code)]
    ;; name-normalized? should be true when raw != coerced
    (-> (expect (:name-normalized? manifest))
        (.toBe true))))

(defn- test-detects-name-was-not-normalized-already-normalized []
  (let [code "^{:epupp/script-name \"github_tweaks.cljs\"}
(ns test)"
        manifest (mp/extract-manifest code)]
    ;; name-normalized? should be false when raw == coerced
    (-> (expect (:name-normalized? manifest))
        (.toBe false))))

(defn- test-returns-nil-script-name-when-missing []
  (let [code "^{:epupp/auto-run-match \"https://example.com/*\"}
(ns test)"
        manifest (mp/extract-manifest code)]
    ;; Missing values from aget are JS undefined, use toBeFalsy
    (-> (expect (:script-name manifest))
        (.toBeFalsy))
    (-> (expect (:raw-script-name manifest))
        (.toBeFalsy))))

(defn- test-handles-invalid-run-at-by-defaulting-instead-of-throwing []
  (let [code "^{:epupp/script-name \"test.cljs\"
  :epupp/run-at \"invalid-value\"}
(ns test)"
        manifest (mp/extract-manifest code)]
    ;; Should default to document-idle instead of throwing
    (-> (expect (:run-at manifest))
        (.toBe "document-idle"))
    ;; Should note that run-at was invalid
    (-> (expect (:run-at-invalid? manifest))
        (.toBe true))
    (-> (expect (:raw-run-at manifest))
        (.toBe "invalid-value"))))

(describe "manifest-parser enhanced features"
          (fn []
            (test "parses auto-run-match as vector of strings" test-parses-auto-run-match-as-vector-of-strings)
            (test "parses auto-run-match as single string" test-parses-auto-run-match-as-single-string)
            (test "collects all epupp/* keys found" test-collects-all-epupp-keys-found)
            (test "identifies unknown epupp/* keys" test-identifies-unknown-epupp-keys)
            (test "returns raw script-name for hint display" test-returns-raw-script-name-for-hint-display)
            (test "applies name normalization" test-applies-name-normalization)
            (test "detects name was normalized (different from raw)" test-detects-name-was-normalized-different-from-raw)
            (test "detects name was NOT normalized (already normalized)" test-detects-name-was-not-normalized-already-normalized)
            (test "returns nil script-name when missing" test-returns-nil-script-name-when-missing)
            (test "handles invalid run-at by defaulting instead of throwing" test-handles-invalid-run-at-by-defaulting-instead-of-throwing)))

;; ============================================================
;; manifest-parser inject feature tests
;; ============================================================

(defn- test-parses-single-inject-as-vector []
  (let [code "^{:epupp/script-name \"test.cljs\"
  :epupp/inject \"scittle://pprint.js\"}
(ns test)"
        manifest (mp/extract-manifest code)]
    (-> (expect (:inject manifest))
        (.toEqual ["scittle://pprint.js"]))))

(defn- test-parses-inject-as-vector-of-strings []
  (let [code "^{:epupp/script-name \"test.cljs\"
  :epupp/inject [\"scittle://pprint.js\" \"scittle://reagent.js\"]}
(ns test)"
        manifest (mp/extract-manifest code)]
    (-> (expect (:inject manifest))
        (.toEqual ["scittle://pprint.js" "scittle://reagent.js"]))))

(defn- test-returns-empty-vector-when-inject-is-missing []
  (let [code "^{:epupp/script-name \"test.cljs\"}
(ns test)"
        manifest (mp/extract-manifest code)]
    (-> (expect (:inject manifest))
        (.toEqual []))))

(defn- test-recognizes-inject-as-known-key []
  (let [code "^{:epupp/script-name \"test.cljs\"
  :epupp/inject \"scittle://pprint.js\"}
(ns test)"
        manifest (mp/extract-manifest code)]
    (-> (expect (:found-keys manifest))
        (.toContain "epupp/inject"))
    ;; inject should NOT be in unknown-keys
    (-> (expect (:unknown-keys manifest))
        (.not.toContain "epupp/inject"))))

(describe "manifest-parser inject feature"
          (fn []
            (test "parses single inject as vector" test-parses-single-inject-as-vector)
            (test "parses inject as vector of strings" test-parses-inject-as-vector-of-strings)
            (test "returns empty vector when inject is missing" test-returns-empty-vector-when-inject-is-missing)
            (test "recognizes inject as known key" test-recognizes-inject-as-known-key)))

;; ============================================================
;; normalize-inject tests
;; ============================================================

(defn- test-normalizes-nil-to-empty-vector []
  (-> (expect (mp/normalize-inject nil))
      (.toEqual [])))

(defn- test-normalizes-string-to-single-element-vector []
  (-> (expect (mp/normalize-inject "scittle://pprint.js"))
      (.toEqual ["scittle://pprint.js"])))

(defn- test-preserves-vector-as-is []
  (-> (expect (mp/normalize-inject ["a" "b" "c"]))
      (.toEqual ["a" "b" "c"])))

(defn- test-normalizes-invalid-types-to-empty-vector []
  (-> (expect (mp/normalize-inject 123))
      (.toEqual []))
  (-> (expect (mp/normalize-inject {}))
      (.toEqual [])))

(describe "normalize-inject"
          (fn []
            (test "normalizes nil to empty vector" test-normalizes-nil-to-empty-vector)
            (test "normalizes string to single-element vector" test-normalizes-string-to-single-element-vector)
            (test "preserves vector as-is" test-preserves-vector-as-is)
            (test "normalizes invalid types to empty vector" test-normalizes-invalid-types-to-empty-vector)))
