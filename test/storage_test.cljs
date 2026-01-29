(ns storage-test
  "Unit tests for storage module, focusing on save-script! behavior."
  (:require ["vitest" :refer [describe test expect]]
            [manifest-parser :as mp]
            [storage :as storage]))

;; ============================================================
;; Mock setup for chrome.storage.local
;; ============================================================

;; We test the logic by extracting manifest and checking the expected behavior
;; The actual storage module requires chrome APIs, so we test the manifest extraction
;; and match handling logic separately.

;; ============================================================
;; Helper Functions (used by tests)
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

;; ============================================================
;; Test Functions
;; ============================================================

;; Auto-run revocation tests

(defn- test-manifest-with-auto-run-match-has-match []
  (let [code "^{:epupp/script-name \"test.cljs\"
  :epupp/auto-run-match \"https://example.com/*\"}
(ns test)"
        manifest (mp/extract-manifest code)
        manifest-match (get manifest "auto-run-match")]
    ;; Manifest has match
    (-> (expect manifest-match)
        (.toBe "https://example.com/*"))))

(defn- test-manifest-without-auto-run-match-is-nil []
  (let [code "^{:epupp/script-name \"test.cljs\"}
(ns test)"
        manifest (mp/extract-manifest code)
        manifest-match (get manifest "auto-run-match")]
    ;; Manifest exists but no match key
    (-> (expect manifest)
        (.toBeTruthy))
    (-> (expect manifest-match)
        (.toBeUndefined))))

(defn- test-no-manifest-returns-nil []
  (let [code "(defn foo [] 42)"
        manifest (mp/extract-manifest code)]
    ;; No manifest at all
    (-> (expect manifest)
        (.toBeUndefined))))

(defn- test-manifest-with-empty-vector-match []
  (let [code "^{:epupp/script-name \"test.cljs\"
  :epupp/auto-run-match []}
(ns test)"
        manifest (mp/extract-manifest code)
        manifest-match (get manifest "auto-run-match")]
    ;; Empty vector is falsy but defined
    (-> (expect (js/Array.isArray manifest-match))
        (.toBe true))
    (-> (expect (.-length manifest-match))
        (.toBe 0))))

;; Helper that implements the auto-run extraction logic

(defn- test-extract-manifest-with-match-returns-match []
  (let [code "^{:epupp/script-name \"test.cljs\"
  :epupp/auto-run-match \"https://example.com/*\"}
(ns test)"
        result (extract-auto-run-from-manifest code nil)]
    (-> (expect (:match result))
        (.toEqual ["https://example.com/*"]))
    (-> (expect (:has-manifest? result))
        (.toBe true))
    (-> (expect (:explicit-empty? result))
        (.toBe false))))

(defn- test-extract-manifest-with-vector-match-returns-vector []
  (let [code "^{:epupp/script-name \"test.cljs\"
  :epupp/auto-run-match [\"https://github.com/*\" \"https://gist.github.com/*\"]}
(ns test)"
        result (extract-auto-run-from-manifest code nil)]
    (-> (expect (:match result))
        (.toEqual ["https://github.com/*" "https://gist.github.com/*"]))))

(defn- test-extract-manifest-without-auto-run-match-revokes []
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
        (.toBe true))))

(defn- test-extract-no-manifest-preserves-existing-match []
  (let [code "(defn foo [] 42)"
        existing-match ["https://preserve-me.com/*"]
        result (extract-auto-run-from-manifest code existing-match)]
    ;; No manifest = preserve existing
    (-> (expect (:match result))
        (.toEqual ["https://preserve-me.com/*"]))
    (-> (expect (:has-manifest? result))
        (.toBe false))
    (-> (expect (:explicit-empty? result))
        (.toBe false))))

(defn- test-extract-no-manifest-no-existing-empty-match []
  (let [code "(defn foo [] 42)"
        result (extract-auto-run-from-manifest code nil)]
    (-> (expect (:match result))
        (.toEqual []))))

(defn- test-extract-manifest-with-empty-vector-explicit-empty []
  (let [code "^{:epupp/script-name \"test.cljs\"
  :epupp/auto-run-match []}
(ns test)"
        existing-match ["https://should-be-cleared.com/*"]
        result (extract-auto-run-from-manifest code existing-match)]
    (-> (expect (:match result))
        (.toEqual []))
    (-> (expect (:explicit-empty? result))
        (.toBe true))))

;; Enabled state logic tests

(defn- test-no-auto-run-always-disabled []
  (-> (expect (determine-enabled-state
               {:has-auto-run? false
                :existing-enabled true
                :is-new? false
                :is-builtin? false}))
      (.toBe false)))

(defn- test-existing-with-auto-run-preserves-enabled []
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
      (.toBe false)))

(defn- test-new-user-script-starts-disabled []
  (-> (expect (determine-enabled-state
               {:has-auto-run? true
                :existing-enabled nil
                :is-new? true
                :is-builtin? false}))
      (.toBe false)))

(defn- test-new-builtin-starts-enabled []
  (-> (expect (determine-enabled-state
               {:has-auto-run? true
                :existing-enabled nil
                :is-new? true
                :is-builtin? true}))
      (.toBe true)))

(defn- test-auto-run-to-manual-resets-enabled []
  ;; Script had auto-run and was enabled
  ;; User removes auto-run-match from manifest
  ;; Script should become disabled (manual-only)
  (-> (expect (determine-enabled-state
               {:has-auto-run? false  ; revoked
                :existing-enabled true
                :is-new? false
                :is-builtin? false}))
      (.toBe false)))

;; Built-in reconciliation tests

(defn- test-removes-stale-builtins []
  (let [bundled-ids (set ["builtin-1"])
        scripts [{:script/id "builtin-1" :script/builtin? true}
                 {:script/id "builtin-2" :script/builtin? true}
                 {:script/id "user-1" :script/builtin? false}]
        updated (storage/remove-stale-builtins scripts bundled-ids)
        stale (storage/stale-builtin-ids scripts bundled-ids)]
    (-> (expect (mapv :script/id updated))
        (.toEqual ["builtin-1" "user-1"]))
    (-> (expect stale)
        (.toEqual ["builtin-2"]))))

(defn- test-existing-builtin-preserves-enabled-state []
  (-> (expect (determine-enabled-state
               {:has-auto-run? true
                :existing-enabled false
                :is-new? false
                :is-builtin? true}))
      (.toBe false))
  (-> (expect (determine-enabled-state
               {:has-auto-run? true
                :existing-enabled true
                :is-new? false
                :is-builtin? true}))
      (.toBe true)))

;; Storage schema migration tests

(defn- test-migrates-unversioned-storage []
  (let [result #js {:scripts #js []
                    :granted-origins #js ["https://example.com/*"]
                    :userAllowedOrigins #js ["https://allowed.example/*"]}
        normalized (storage/normalize-storage-result result)]
    (-> (expect (:storage/schema-version normalized))
        (.toBe 1))
    (-> (expect (:storage/granted-origins normalized))
        (.toEqual ["https://example.com/*"]))
    (-> (expect (:storage/remove-keys normalized))
        (.toEqual ["granted-origins"]))
    (-> (expect (:storage/migrated? normalized))
        (.toBe true))))

(defn- test-keeps-versioned-storage-unchanged []
  (let [result #js {:schemaVersion 1
                    :scripts #js []
                    :grantedOrigins #js ["https://example.com/*"]
                    :userAllowedOrigins #js []}
        normalized (storage/normalize-storage-result result)]
    (-> (expect (:storage/schema-version normalized))
        (.toBe 1))
    (-> (expect (:storage/granted-origins normalized))
        (.toEqual ["https://example.com/*"]))
    (-> (expect (:storage/remove-keys normalized))
        (.toEqual []))
    (-> (expect (:storage/migrated? normalized))
        (.toBe false))))

;; ============================================================
;; Test Registration
;; ============================================================

(describe "Auto-run revocation logic"
          (fn []
            (test "manifest with auto-run-match → script should have match" test-manifest-with-auto-run-match-has-match)
            (test "manifest without auto-run-match → match should be nil" test-manifest-without-auto-run-match-is-nil)
            (test "no manifest (plain code) → manifest is nil" test-no-manifest-returns-nil)
            (test "manifest with empty vector match → match is empty" test-manifest-with-empty-vector-match)))

(describe "extract-auto-run-from-manifest helper"
          (fn []
            (test "manifest with match → returns match" test-extract-manifest-with-match-returns-match)
            (test "manifest with vector match → returns vector" test-extract-manifest-with-vector-match-returns-vector)
            (test "manifest without auto-run-match → revokes (empty match)" test-extract-manifest-without-auto-run-match-revokes)
            (test "no manifest → preserves existing match" test-extract-no-manifest-preserves-existing-match)
            (test "no manifest and no existing → empty match" test-extract-no-manifest-no-existing-empty-match)
            (test "manifest with empty vector match → explicit empty" test-extract-manifest-with-empty-vector-explicit-empty)))

(describe "determine-enabled-state"
          (fn []
            (test "no auto-run patterns → always disabled" test-no-auto-run-always-disabled)
            (test "existing script with auto-run → preserves enabled state" test-existing-with-auto-run-preserves-enabled)
            (test "new user script with auto-run → starts disabled" test-new-user-script-starts-disabled)
            (test "new built-in with auto-run → starts enabled" test-new-builtin-starts-enabled)
            (test "auto-run → manual transition resets enabled" test-auto-run-to-manual-resets-enabled)))

(describe "built-in reconciliation"
          (fn []
            (test "removes stale built-ins" test-removes-stale-builtins)
            (test "existing built-in preserves enabled state" test-existing-builtin-preserves-enabled-state)))

(describe "storage schema migration"
  (fn []
    (test "migrates unversioned storage and renames granted-origins" test-migrates-unversioned-storage)
    (test "keeps versioned storage unchanged when already v1" test-keeps-versioned-storage-unchanged)))

;; Built-in update detection tests

(defn- test-builtin-identical-no-update []
  (let [script {:script/id "builtin-1"
                :script/code "(ns test)"
                :script/name "test.cljs"
                :script/match ["https://example.com/*"]
                :script/description "Test"
                :script/run-at "document-idle"
                :script/inject ["scittle://reagent.js"]}]
    (-> (expect (storage/builtin-update-needed? script script))
        (.toBe false))))

(defn- test-builtin-code-differs-needs-update []
  (let [existing {:script/id "builtin-1"
                  :script/code "(ns test)"
                  :script/name "test.cljs"}
        desired {:script/id "builtin-1"
                 :script/code "(ns test-updated)"
                 :script/name "test.cljs"}]
    (-> (expect (storage/builtin-update-needed? existing desired))
        (.toBe true))))

(defn- test-builtin-name-differs-needs-update []
  (let [existing {:script/id "builtin-1"
                  :script/code "(ns test)"
                  :script/name "test.cljs"}
        desired {:script/id "builtin-1"
                 :script/code "(ns test)"
                 :script/name "updated.cljs"}]
    (-> (expect (storage/builtin-update-needed? existing desired))
        (.toBe true))))

(defn- test-builtin-match-differs-needs-update []
  (let [existing {:script/id "builtin-1"
                  :script/code "(ns test)"
                  :script/match ["https://example.com/*"]}
        desired {:script/id "builtin-1"
                 :script/code "(ns test)"
                 :script/match ["https://other.com/*"]}]
    (-> (expect (storage/builtin-update-needed? existing desired))
        (.toBe true))))

(defn- test-builtin-description-differs-needs-update []
  (let [existing {:script/id "builtin-1"
                  :script/code "(ns test)"
                  :script/description "Old"}
        desired {:script/id "builtin-1"
                 :script/code "(ns test)"
                 :script/description "New"}]
    (-> (expect (storage/builtin-update-needed? existing desired))
        (.toBe true))))

(defn- test-builtin-run-at-differs-needs-update []
  (let [existing {:script/id "builtin-1"
                  :script/code "(ns test)"
                  :script/run-at "document-start"}
        desired {:script/id "builtin-1"
                 :script/code "(ns test)"
                 :script/run-at "document-idle"}]
    (-> (expect (storage/builtin-update-needed? existing desired))
        (.toBe true))))

(defn- test-builtin-inject-differs-needs-update []
  (let [existing {:script/id "builtin-1"
                  :script/code "(ns test)"
                  :script/inject ["scittle://reagent.js"]}
        desired {:script/id "builtin-1"
                 :script/code "(ns test)"
                 :script/inject ["scittle://re-frame.js"]}]
    (-> (expect (storage/builtin-update-needed? existing desired))
        (.toBe true))))

(defn- test-builtin-nil-existing-needs-update []
  (let [desired {:script/id "builtin-1"
                 :script/code "(ns test)"}]
    (-> (expect (storage/builtin-update-needed? nil desired))
        (.toBe true))))

(describe "Built-in script update detection"
  (fn []
    (test "Identical scripts → no update needed" test-builtin-identical-no-update)
    (test "Code differs → update needed" test-builtin-code-differs-needs-update)
    (test "Name differs → update needed" test-builtin-name-differs-needs-update)
    (test "Match patterns differ → update needed" test-builtin-match-differs-needs-update)
    (test "Description differs → update needed" test-builtin-description-differs-needs-update)
    (test "Run-at differs → update needed" test-builtin-run-at-differs-needs-update)
    (test "Inject differs → update needed" test-builtin-inject-differs-needs-update)
    (test "Nil existing (new built-in) → update needed" test-builtin-nil-existing-needs-update)))



;; ============================================================
;; Built-in Script Building from Manifest Tests
;; ============================================================

(defn- test-build-bundled-complete-manifest-all-fields []
  (let [bundled {:script/id "builtin-1"
                 :path "userscripts/test.cljs"
                 :name "test.cljs"}
        code "{:epupp/script-name \"complete.cljs\"
 :epupp/description \"A complete script\"
 :epupp/auto-run-match [\"https://example.com/*\" \"https://test.com/*\"]
 :epupp/run-at \"document-start\"
 :epupp/inject [\"scittle://reagent.js\" \"scittle://re-frame.js\"]}

(ns test-script)
(println \"hello\")"
        result (storage/build-bundled-script bundled code)]
    (-> (expect (:script/id result))
        (.toBe "builtin-1"))
    (-> (expect (:script/code result))
        (.toBe code))
    (-> (expect (:script/builtin? result))
        (.toBe true))
    (-> (expect (:script/name result))
        (.toBe "complete.cljs"))
    (-> (expect (:script/description result))
        (.toBe "A complete script"))
    (-> (expect (:script/match result))
        (.toEqual ["https://example.com/*" "https://test.com/*"]))
    (-> (expect (:script/run-at result))
        (.toBe "document-start"))
    (-> (expect (:script/inject result))
        (.toEqual ["scittle://reagent.js" "scittle://re-frame.js"]))))

(defn- test-build-bundled-minimal-manifest-only-script-name []
  (let [bundled {:script/id "builtin-2"
                 :path "userscripts/minimal.cljs"
                 :name "minimal.cljs"}
        code "{:epupp/script-name \"minimal.cljs\"}

(ns minimal)
(println \"minimal\")"
        result (storage/build-bundled-script bundled code)]
    (-> (expect (:script/id result))
        (.toBe "builtin-2"))
    (-> (expect (:script/code result))
        (.toBe code))
    (-> (expect (:script/builtin? result))
        (.toBe true))
    (-> (expect (:script/name result))
        (.toBe "minimal.cljs"))
    ;; Optional fields should not be present when not in manifest
    (-> (expect (contains? result :script/description))
        (.toBe false))
    ;; Manifest present but no auto-run-match key -> match is empty array
    (-> (expect (:script/match result))
        (.toEqual []))
    ;; Manifest present but no inject key -> inject not set
    (-> (expect (contains? result :script/inject))
        (.toBe false))))

(defn- test-build-bundled-manifest-with-string-inject []
  (let [bundled {:script/id "builtin-3"
                 :path "userscripts/string-inject.cljs"
                 :name "string-inject.cljs"}
        code "{:epupp/script-name \"string-inject.cljs\"
 :epupp/inject \"scittle://reagent.js\"}

(ns string-inject)"
        result (storage/build-bundled-script bundled code)]
    ;; String inject should be normalized to vector
    (-> (expect (:script/inject result))
        (.toEqual ["scittle://reagent.js"]))))

(defn- test-build-bundled-manifest-with-array-inject []
  (let [bundled {:script/id "builtin-4"
                 :path "userscripts/array-inject.cljs"
                 :name "array-inject.cljs"}
        code "{:epupp/script-name \"array-inject.cljs\"
 :epupp/inject [\"scittle://reagent.js\" \"scittle://pprint.js\"]}

(ns array-inject)"
        result (storage/build-bundled-script bundled code)]
    (-> (expect (:script/inject result))
        (.toEqual ["scittle://reagent.js" "scittle://pprint.js"]))))

(defn- test-build-bundled-manifest-with-match-patterns []
  (let [bundled {:script/id "builtin-5"
                 :path "userscripts/with-match.cljs"
                 :name "with-match.cljs"}
        code "{:epupp/script-name \"with-match.cljs\"
 :epupp/auto-run-match \"https://github.com/*\"}

(ns with-match)"
        result (storage/build-bundled-script bundled code)]
    (-> (expect (:script/match result))
        (.toEqual ["https://github.com/*"]))))

(defn- test-build-bundled-manifest-without-match-manual-only []
  (let [bundled {:script/id "builtin-6"
                 :path "userscripts/manual-only.cljs"
                 :name "manual-only.cljs"}
        code "{:epupp/script-name \"manual-only.cljs\"
 :epupp/description \"Manual execution only\"}

(ns manual-only)"
        result (storage/build-bundled-script bundled code)]
    ;; When manifest present but no auto-run-match key, match should be empty
    (-> (expect (:script/match result))
        (.toEqual []))))

(defn- test-build-bundled-invalid-run-at-defaults-to-document-idle []
  (let [bundled {:script/id "builtin-7"
                 :path "userscripts/invalid-run-at.cljs"
                 :name "invalid-run-at.cljs"}
        code "{:epupp/script-name \"invalid-run-at.cljs\"
 :epupp/run-at \"invalid-timing\"}

(ns invalid-run-at)"
        result (storage/build-bundled-script bundled code)]
    ;; Invalid run-at should default to document-idle
    (-> (expect (:script/run-at result))
        (.toBe "document-idle"))))

(defn- test-build-bundled-uses-fallback-name-when-no-manifest []
  (let [bundled {:script/id "builtin-8"
                 :path "userscripts/no-manifest.cljs"
                 :name "fallback-name.cljs"}
        code "(ns no-manifest)
(println \"no manifest at all\")"
        result (storage/build-bundled-script bundled code)]
    ;; Should use bundled :name as fallback
    (-> (expect (:script/name result))
        (.toBe "fallback-name.cljs"))
    (-> (expect (:script/builtin? result))
        (.toBe true))
    ;; No manifest -> inject is not set (undefined)
    (-> (expect (contains? result :script/inject))
        (.toBe false))))

(defn- test-build-bundled-manifest-with-string-match []
  (let [bundled {:script/id "builtin-9"
                 :path "userscripts/string-match.cljs"
                 :name "string-match.cljs"}
        code "{:epupp/script-name \"string-match.cljs\"
 :epupp/auto-run-match \"https://example.com/*\"}

(ns string-match)"
        result (storage/build-bundled-script bundled code)]
    ;; String match should be normalized to vector
    (-> (expect (:script/match result))
        (.toEqual ["https://example.com/*"]))))

(defn- test-build-bundled-manifest-with-empty-match-array []
  (let [bundled {:script/id "builtin-10"
                 :path "userscripts/empty-match.cljs"
                 :name "empty-match.cljs"}
        code "{:epupp/script-name \"empty-match.cljs\"
 :epupp/auto-run-match []}

(ns empty-match)"
        result (storage/build-bundled-script bundled code)]
    ;; Explicit empty match should be preserved
    (-> (expect (:script/match result))
        (.toEqual []))))

(describe "Built-in script building from manifest"
          (fn []
            (test "complete manifest with all fields" test-build-bundled-complete-manifest-all-fields)
            (test "minimal manifest (only script-name)" test-build-bundled-minimal-manifest-only-script-name)
            (test "manifest with string inject" test-build-bundled-manifest-with-string-inject)
            (test "manifest with array inject" test-build-bundled-manifest-with-array-inject)
            (test "manifest with match patterns" test-build-bundled-manifest-with-match-patterns)
            (test "manifest without match (manual-only)" test-build-bundled-manifest-without-match-manual-only)
            (test "invalid run-at defaults to document-idle" test-build-bundled-invalid-run-at-defaults-to-document-idle)
            (test "uses fallback name when no manifest" test-build-bundled-uses-fallback-name-when-no-manifest)
            (test "manifest with string match" test-build-bundled-manifest-with-string-match)
            (test "manifest with empty match array" test-build-bundled-manifest-with-empty-match-array)))
