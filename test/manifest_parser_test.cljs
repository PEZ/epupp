(ns manifest-parser-test
  (:require ["vitest" :refer [describe test expect]]
            [manifest-parser :as mp]))

(describe "manifest-parser"
          (fn []
            (test "extracts manifest from metadata map"
                  (fn []
                    (let [code "^{:epupp/script-name \"test.cljs\"\n  :epupp/site-match \"https://example.com/*\"\n  :epupp/description \"desc\"\n  :epupp/run-at \"document-start\"}\n(ns test)"
                          manifest (mp/extract-manifest code)]
                      ;; script-name is normalized (test.cljs stays test.cljs)
                      (-> (expect (:script-name manifest))
                          (.toBe "test.cljs"))
                      (-> (expect (:site-match manifest))
                          (.toBe "https://example.com/*"))
                      (-> (expect (:description manifest))
                          (.toBe "desc"))
                      (-> (expect (:run-at manifest))
                          (.toBe "document-start")))))

            (test "defaults run-at when omitted"
                  (fn []
                    (let [code "^{:epupp/script-name \"test.cljs\"}\n(ns test)"
                          manifest (mp/extract-manifest code)]
                      (-> (expect (:run-at manifest))
                          (.toBe "document-idle"))
                      (-> (expect (mp/get-run-at code))
                          (.toBe "document-idle")))))

            (test "defaults invalid run-at instead of throwing"
                  (fn []
                    (let [code "^{:epupp/script-name \"test.cljs\"\n  :epupp/run-at \"nope\"}\n(ns test)"
                          manifest (mp/extract-manifest code)]
                      ;; Invalid run-at should default instead of throwing
                      (-> (expect (:run-at manifest))
                          (.toBe "document-idle"))
                      (-> (expect (:run-at-invalid? manifest))
                          (.toBe true))
                      (-> (expect (:raw-run-at manifest))
                          (.toBe "nope")))))

            (test "returns nil for code without manifest"
                  (fn []
                    (let [code "(defn foo [] 42)"
                          manifest (mp/extract-manifest code)]
                      ;; Clojure nil becomes JS undefined (no epupp keys found)
                      (-> (expect manifest)
                          (.toBeUndefined)))))

            (test "has-manifest? detects presence"
                  (fn []
                    (-> (expect (mp/has-manifest? "^{:epupp/script-name \"x.cljs\"} (ns x)"))
                        (.toBe true))
                    (-> (expect (mp/has-manifest? "(ns x)"))
                        (.toBe false))))

            (test "allows whitespace before manifest"
                  (fn []
                    (let [code "   \n\n^{:epupp/script-name \"test.cljs\"}\n(ns test)"
                          manifest (mp/extract-manifest code)]
                      ;; Already normalized name stays as-is
                      (-> (expect (:script-name manifest))
                          (.toBe "test.cljs")))))

            (test "allows line comments before manifest"
                  (fn []
                    (let [code ";; My awesome script\n;; Does cool things\n\n^{:epupp/script-name \"test.cljs\"}\n(ns test)"
                          manifest (mp/extract-manifest code)]
                      (-> (expect (:script-name manifest))
                          (.toBe "test.cljs")))))))

;; ============================================================
;; Phase 1: Enhanced manifest parsing tests
;; ============================================================

(describe "manifest-parser enhanced features"
          (fn []
            (test "parses site-match as vector of strings"
                  (fn []
                    (let [code "^{:epupp/script-name \"test.cljs\"
  :epupp/site-match [\"https://github.com/*\" \"https://gist.github.com/*\"]}
(ns test)"
                          manifest (mp/extract-manifest code)]
                      ;; site-match should be preserved as vector
                      (-> (expect (:site-match manifest))
                          (.toEqual ["https://github.com/*" "https://gist.github.com/*"])))))

            (test "parses site-match as single string"
                  (fn []
                    (let [code "^{:epupp/script-name \"test.cljs\"
  :epupp/site-match \"https://github.com/*\"}
(ns test)"
                          manifest (mp/extract-manifest code)]
                      ;; Single string should be preserved as-is
                      (-> (expect (:site-match manifest))
                          (.toBe "https://github.com/*")))))

            (test "collects all epupp/* keys found"
                  (fn []
                    (let [code "^{:epupp/script-name \"test.cljs\"
  :epupp/site-match \"https://example.com/*\"
  :epupp/description \"A test script\"
  :epupp/run-at \"document-start\"
  :epupp/unknown-key \"should be noted\"}
(ns test)"
                          manifest (mp/extract-manifest code)]
                      ;; Should have found all epupp keys
                      (-> (expect (:found-keys manifest))
                          (.toContain "epupp/script-name"))
                      (-> (expect (:found-keys manifest))
                          (.toContain "epupp/unknown-key")))))

            (test "identifies unknown epupp/* keys"
                  (fn []
                    (let [code "^{:epupp/script-name \"test.cljs\"
  :epupp/site-match \"https://example.com/*\"
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
                          (.not.toContain "epupp/site-match")))))

            (test "returns raw script-name for hint display"
                  (fn []
                    (let [code "^{:epupp/script-name \"GitHub Tweaks\"}
(ns test)"
                          manifest (mp/extract-manifest code)]
                      ;; raw-script-name preserves original input
                      (-> (expect (:raw-script-name manifest))
                          (.toBe "GitHub Tweaks"))
                      ;; script-name is normalized
                      (-> (expect (:script-name manifest))
                          (.toBe "github_tweaks.cljs")))))

            (test "applies name normalization"
                  (fn []
                    (let [code "^{:epupp/script-name \"My Cool Script\"}
(ns test)"
                          manifest (mp/extract-manifest code)]
                      (-> (expect (:script-name manifest))
                          (.toBe "my_cool_script.cljs")))))

            (test "detects name was normalized (different from raw)"
                  (fn []
                    (let [code "^{:epupp/script-name \"GitHub Tweaks\"}
(ns test)"
                          manifest (mp/extract-manifest code)]
                      ;; name-normalized? should be true when raw != coerced
                      (-> (expect (:name-normalized? manifest))
                          (.toBe true)))))

            (test "detects name was NOT normalized (already normalized)"
                  (fn []
                    (let [code "^{:epupp/script-name \"github_tweaks.cljs\"}
(ns test)"
                          manifest (mp/extract-manifest code)]
                      ;; name-normalized? should be false when raw == coerced
                      (-> (expect (:name-normalized? manifest))
                          (.toBe false)))))

            (test "returns nil script-name when missing"
                  (fn []
                    (let [code "^{:epupp/site-match \"https://example.com/*\"}
(ns test)"
                          manifest (mp/extract-manifest code)]
                      ;; Missing values from aget are JS undefined, use toBeFalsy
                      (-> (expect (:script-name manifest))
                          (.toBeFalsy))
                      (-> (expect (:raw-script-name manifest))
                          (.toBeFalsy)))))

            (test "handles invalid run-at by defaulting instead of throwing"
                  (fn []
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
                          (.toBe "invalid-value")))))))

;; ============================================================
;; Phase 2: Inject parsing tests
;; ============================================================

(describe "manifest-parser inject feature"
          (fn []
            (test "parses single inject as vector"
                  (fn []
                    (let [code "^{:epupp/script-name \"test.cljs\"
  :epupp/inject \"scittle://pprint.js\"}
(ns test)"
                          manifest (mp/extract-manifest code)]
                      (-> (expect (:inject manifest))
                          (.toEqual ["scittle://pprint.js"])))))

            (test "parses inject as vector of strings"
                  (fn []
                    (let [code "^{:epupp/script-name \"test.cljs\"
  :epupp/inject [\"scittle://pprint.js\" \"scittle://reagent.js\"]}
(ns test)"
                          manifest (mp/extract-manifest code)]
                      (-> (expect (:inject manifest))
                          (.toEqual ["scittle://pprint.js" "scittle://reagent.js"])))))

            (test "returns empty vector when inject is missing"
                  (fn []
                    (let [code "^{:epupp/script-name \"test.cljs\"}
(ns test)"
                          manifest (mp/extract-manifest code)]
                      (-> (expect (:inject manifest))
                          (.toEqual [])))))

            (test "recognizes inject as known key"
                  (fn []
                    (let [code "^{:epupp/script-name \"test.cljs\"
  :epupp/inject \"scittle://pprint.js\"}
(ns test)"
                          manifest (mp/extract-manifest code)]
                      (-> (expect (:found-keys manifest))
                          (.toContain "epupp/inject"))
                      ;; inject should NOT be in unknown-keys
                      (-> (expect (:unknown-keys manifest))
                          (.not.toContain "epupp/inject")))))))

(describe "normalize-inject"
          (fn []
            (test "normalizes nil to empty vector"
                  (fn []
                    (-> (expect (mp/normalize-inject nil))
                        (.toEqual []))))

            (test "normalizes string to single-element vector"
                  (fn []
                    (-> (expect (mp/normalize-inject "scittle://pprint.js"))
                        (.toEqual ["scittle://pprint.js"]))))

            (test "preserves vector as-is"
                  (fn []
                    (-> (expect (mp/normalize-inject ["a" "b" "c"]))
                        (.toEqual ["a" "b" "c"]))))

            (test "normalizes invalid types to empty vector"
                  (fn []
                    (-> (expect (mp/normalize-inject 123))
                        (.toEqual []))
                    (-> (expect (mp/normalize-inject {}))
                        (.toEqual []))))))
