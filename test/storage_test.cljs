(ns storage-test
  "Unit tests for storage module, focusing on save-script! behavior."
  (:require ["vitest" :refer [describe test expect]]
            [manifest-parser :as mp]))

;; ============================================================
;; Mock setup for chrome.storage.local
;; ============================================================

;; We test the logic by extracting manifest and checking the expected behavior
;; The actual storage module requires chrome APIs, so we test the manifest extraction
;; and match handling logic separately.

;; ============================================================
;; Phase 6: Auto-run revocation tests
;; ============================================================

(describe "Auto-run revocation logic"
          (fn []
            (test "manifest with auto-run-match → script should have match"
                  (fn []
                    (let [code "^{:epupp/script-name \"test.cljs\"
  :epupp/auto-run-match \"https://example.com/*\"}
(ns test)"
                          manifest (mp/extract-manifest code)
                          manifest-match (get manifest "auto-run-match")]
                      ;; Manifest has match
                      (-> (expect manifest-match)
                          (.toBe "https://example.com/*")))))

            (test "manifest without auto-run-match → match should be nil"
                  (fn []
                    (let [code "^{:epupp/script-name \"test.cljs\"}
(ns test)"
                          manifest (mp/extract-manifest code)
                          manifest-match (get manifest "auto-run-match")]
                      ;; Manifest exists but no match key
                      (-> (expect manifest)
                          (.toBeTruthy))
                      (-> (expect manifest-match)
                          (.toBeUndefined)))))

            (test "no manifest (plain code) → manifest is nil"
                  (fn []
                    (let [code "(defn foo [] 42)"
                          manifest (mp/extract-manifest code)]
                      ;; No manifest at all
                      (-> (expect manifest)
                          (.toBeUndefined)))))

            (test "manifest with empty vector match → match is empty"
                  (fn []
                    (let [code "^{:epupp/script-name \"test.cljs\"
  :epupp/auto-run-match []}
(ns test)"
                          manifest (mp/extract-manifest code)
                          manifest-match (get manifest "auto-run-match")]
                      ;; Empty vector is falsy but defined
                      (-> (expect (js/Array.isArray manifest-match))
                          (.toBe true))
                      (-> (expect (.-length manifest-match))
                          (.toBe 0)))))))

;; ============================================================
;; Helper that implements the auto-run extraction logic
;; This mirrors what save-script! should do
;; ============================================================

(defn extract-auto-run-from-manifest
  "Extract auto-run match from manifest, handling the key scenarios:
   1. Manifest present with match → use match
   2. Manifest present without match → explicit empty (revocation)
   3. No manifest → preserve existing (no change)

   Returns {:match [...] :has-manifest? bool :explicit-empty? bool}"
  [code existing-match]
  (let [manifest (try (mp/extract-manifest code)
                      (catch :default _ nil))
        has-manifest? (some? manifest)]
    (cond
      ;; Manifest has auto-run-match key
      (and has-manifest? (js/Object.hasOwn manifest "auto-run-match"))
      (let [match-value (get manifest "auto-run-match")
            normalized (cond
                         (nil? match-value) []
                         (string? match-value) (if (empty? match-value) [] [match-value])
                         (js/Array.isArray match-value) (vec match-value)
                         :else [])]
        {:match normalized
         :has-manifest? true
         :explicit-empty? (empty? normalized)})

      ;; Manifest present but no auto-run-match key → revocation
      has-manifest?
      {:match []
       :has-manifest? true
       :explicit-empty? true}

      ;; No manifest → preserve existing
      :else
      {:match (or existing-match [])
       :has-manifest? false
       :explicit-empty? false})))

(describe "extract-auto-run-from-manifest helper"
          (fn []
            (test "manifest with match → returns match"
                  (fn []
                    (let [code "^{:epupp/script-name \"test.cljs\"
  :epupp/auto-run-match \"https://example.com/*\"}
(ns test)"
                          result (extract-auto-run-from-manifest code nil)]
                      (-> (expect (:match result))
                          (.toEqual ["https://example.com/*"]))
                      (-> (expect (:has-manifest? result))
                          (.toBe true))
                      (-> (expect (:explicit-empty? result))
                          (.toBe false)))))

            (test "manifest with vector match → returns vector"
                  (fn []
                    (let [code "^{:epupp/script-name \"test.cljs\"
  :epupp/auto-run-match [\"https://github.com/*\" \"https://gist.github.com/*\"]}
(ns test)"
                          result (extract-auto-run-from-manifest code nil)]
                      (-> (expect (:match result))
                          (.toEqual ["https://github.com/*" "https://gist.github.com/*"])))))

            (test "manifest without auto-run-match → revokes (empty match)"
                  (fn []
                    (let [code "^{:epupp/script-name \"test.cljs\"}
(ns test)"
                          existing-match ["https://old-pattern.com/*"]
                          result (extract-auto-run-from-manifest code existing-match)]
                      ;; Key insight: manifest present but no match = explicit revocation
                      (-> (expect (:match result))
                          (.toEqual []))
                      (-> (expect (:has-manifest? result))
                          (.toBe true))
                      (-> (expect (:explicit-empty? result))
                          (.toBe true)))))

            (test "no manifest → preserves existing match"
                  (fn []
                    (let [code "(defn foo [] 42)"
                          existing-match ["https://preserve-me.com/*"]
                          result (extract-auto-run-from-manifest code existing-match)]
                      ;; No manifest = preserve existing
                      (-> (expect (:match result))
                          (.toEqual ["https://preserve-me.com/*"]))
                      (-> (expect (:has-manifest? result))
                          (.toBe false))
                      (-> (expect (:explicit-empty? result))
                          (.toBe false)))))

            (test "no manifest and no existing → empty match"
                  (fn []
                    (let [code "(defn foo [] 42)"
                          result (extract-auto-run-from-manifest code nil)]
                      (-> (expect (:match result))
                          (.toEqual [])))))

            (test "manifest with empty vector match → explicit empty"
                  (fn []
                    (let [code "^{:epupp/script-name \"test.cljs\"
  :epupp/auto-run-match []}
(ns test)"
                          existing-match ["https://should-be-cleared.com/*"]
                          result (extract-auto-run-from-manifest code existing-match)]
                      (-> (expect (:match result))
                          (.toEqual []))
                      (-> (expect (:explicit-empty? result))
                          (.toBe true)))))))

;; ============================================================
;; Enabled state logic tests
;; ============================================================

(defn determine-enabled-state
  "Determine the enabled state for a script based on:
   1. Whether it has auto-run patterns
   2. Whether it's a new or existing script
   3. Whether it's a built-in script

   Logic:
   - No auto-run patterns → always false (manual-only)
   - Has patterns + existing → preserve existing enabled state
   - Has patterns + new + builtin → true
   - Has patterns + new + not builtin → false"
  [{:keys [has-auto-run? existing-enabled is-new? is-builtin?]}]
  (cond
    ;; No auto-run = disabled (manual-only)
    (not has-auto-run?) false
    ;; Existing script with auto-run = preserve
    (not is-new?) existing-enabled
    ;; New built-in = enabled
    is-builtin? true
    ;; New user script = disabled
    :else false))

(describe "determine-enabled-state"
          (fn []
            (test "no auto-run patterns → always disabled"
                  (fn []
                    (-> (expect (determine-enabled-state
                                 {:has-auto-run? false
                                  :existing-enabled true
                                  :is-new? false
                                  :is-builtin? false}))
                        (.toBe false))))

            (test "existing script with auto-run → preserves enabled state"
                  (fn []
                    (-> (expect (determine-enabled-state
                                 {:has-auto-run? true
                                  :existing-enabled true
                                  :is-new? false
                                  :is-builtin? false}))
                        (.toBe true))
                    (-> (expect (determine-enabled-state
                                 {:has-auto-run? true
                                  :existing-enabled false
                                  :is-new? false
                                  :is-builtin? false}))
                        (.toBe false))))

            (test "new user script with auto-run → starts disabled"
                  (fn []
                    (-> (expect (determine-enabled-state
                                 {:has-auto-run? true
                                  :existing-enabled nil
                                  :is-new? true
                                  :is-builtin? false}))
                        (.toBe false))))

            (test "new built-in with auto-run → starts enabled"
                  (fn []
                    (-> (expect (determine-enabled-state
                                 {:has-auto-run? true
                                  :existing-enabled nil
                                  :is-new? true
                                  :is-builtin? true}))
                        (.toBe true))))

            (test "auto-run → manual transition resets enabled"
                  (fn []
                    ;; Script had auto-run and was enabled
                    ;; User removes auto-run-match from manifest
                    ;; Script should become disabled (manual-only)
                    (-> (expect (determine-enabled-state
                                 {:has-auto-run? false  ; revoked
                                  :existing-enabled true
                                  :is-new? false
                                  :is-builtin? false}))
                        (.toBe false))))))
