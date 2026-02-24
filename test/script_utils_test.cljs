(ns script-utils-test
  "Unit and property-style tests for script name validation."
  (:require ["vitest" :refer [describe test expect afterEach vi]]
            [manifest-parser :as mp]
            [script-utils :as script-utils]))

;; ============================================================
;; Helpers for deterministic property-style tests
;; ============================================================

(defn- lcg-next [seed]
  (mod (+ (* seed 1664525) 1013904223) 4294967296))

(defn- lcg-rand-int [seed n]
  (let [next (lcg-next seed)]
    [next (mod next n)]))

(defn- rand-char [seed]
  (let [chars "abcdefghijklmnopqrstuvwxyz0123456789_"
        [seed idx] (lcg-rand-int seed (.-length chars))]
    [seed (.charAt chars idx)]))

(defn- gen-segment [seed]
  (let [[seed len] (lcg-rand-int seed 8)
        len (+ 1 len)]
    (loop [seed seed i 0 out ""]
      (if (< i len)
        (let [[seed ch] (rand-char seed)]
          (recur seed (inc i) (str out ch)))
        [seed out]))))

(defn- gen-valid-name [seed]
  (let [[seed seg-count] (lcg-rand-int seed 3)
        seg-count (+ 1 seg-count)]
    (loop [seed seed i 0 segs []]
      (if (< i seg-count)
        (let [[seed seg] (gen-segment seed)
              seg (if (and (= i 0) (= seg "epupp")) "epuppx" seg)]
          (recur seed (inc i) (conj segs seg)))
        [seed (str (.join segs "/") ".cljs")]))))

;; ============================================================
;; Test Functions
;; ============================================================

;; validate-script-name tests

(defn- test-validate-accepts-valid-names []
  (-> (expect (script-utils/validate-script-name "test.cljs"))
      (.toBe nil))
  (-> (expect (script-utils/validate-script-name "folder/test.cljs"))
      (.toBe nil)))

(defn- test-validate-rejects-reserved-namespace []
  (-> (expect (script-utils/validate-script-name "epupp/test.cljs"))
      (.toContain "reserved namespace")))

(defn- test-validate-rejects-leading-slash []
  (-> (expect (script-utils/validate-script-name "/test.cljs"))
      (.toContain "start with '/'")))

(defn- test-validate-rejects-dot-slash-and-dot-dot-slash []
  (-> (expect (script-utils/validate-script-name "./test.cljs"))
      (.toContain "./' or '../'"))
  (-> (expect (script-utils/validate-script-name "../test.cljs"))
      (.toContain "./' or '../'"))
  (-> (expect (script-utils/validate-script-name "folder/../test.cljs"))
      (.toContain "./' or '../'")))

(defn- test-validate-property-valid-names-accepted []
  (loop [seed 1 idx 0]
    (when (< idx 200)
      (let [[seed name] (gen-valid-name seed)]
        (-> (expect (script-utils/validate-script-name name))
            (.toBe nil))
        (recur seed (inc idx))))))

(defn- test-validate-property-reserved-namespace-rejected []
  (loop [seed 2 idx 0]
    (when (< idx 200)
      (let [[seed name] (gen-valid-name seed)
            bad-name (str "epupp/" name)
            err (script-utils/validate-script-name bad-name)]
        (-> (expect err) (.toContain "reserved namespace"))
        (recur seed (inc idx))))))

(defn- test-validate-property-leading-slash-rejected []
  (loop [seed 3 idx 0]
    (when (< idx 200)
      (let [[seed name] (gen-valid-name seed)
            bad-name (str "/" name)
            err (script-utils/validate-script-name bad-name)]
        (-> (expect err) (.toContain "start with '/'"))
        (recur seed (inc idx))))))

(defn- test-validate-property-path-traversal-rejected []
  (loop [seed 4 idx 0]
    (when (< idx 200)
      (let [[seed name] (gen-valid-name seed)
            bad-name (str "foo/../" name)
            err (script-utils/validate-script-name bad-name)]
        (-> (expect err) (.toContain "./' or '../'"))
        (recur seed (inc idx))))))

;; derive-script-fields tests

(defn- test-derive-fields-from-manifest []
  (let [code "^{:epupp/script-name \"derived.cljs\"\n  :epupp/auto-run-match \"https://example.com/*\"\n  :epupp/description \"Example\"\n  :epupp/run-at \"document-end\"\n  :epupp/inject \"scittle://reagent.js\"}\n(ns derived)"
        script {:script/id "script-1" :script/code code}
        manifest (mp/extract-manifest code)
        derived (script-utils/derive-script-fields script manifest)]
    (-> (expect (:script/name derived))
        (.toBe "derived.cljs"))
    (-> (expect (:script/match derived))
        (.toEqual ["https://example.com/*"]))
    (-> (expect (:script/description derived))
        (.toBe "Example"))
    (-> (expect (:script/run-at derived))
        (.toBe "document-end"))
    (-> (expect (:script/inject derived))
        (.toEqual ["scittle://reagent.js"]))))

(defn- test-derive-manifest-without-auto-run-clears-match []
  (let [code "^{:epupp/script-name \"manual.cljs\"}\n(ns manual)"
        script {:script/id "script-2"
                :script/code code
                :script/match ["https://old.example/*"]}
        manifest (mp/extract-manifest code)
        derived (script-utils/derive-script-fields script manifest)]
    (-> (expect (:script/match derived))
        (.toEqual []))))

(defn- test-derive-nil-manifest-preserves-existing-fields []
  (let [script {:script/id "script-3"
                :script/code "(ns no-manifest)"
                :script/name "old.cljs"
                :script/description "Old"
                :script/match ["https://old.example/*"]
                :script/run-at "document-start"
                :script/inject ["scittle://reagent.js"]}
        derived (script-utils/derive-script-fields script nil)]
    (-> (expect (:script/name derived))
        (.toBe "old.cljs"))
    (-> (expect (:script/match derived))
        (.toEqual ["https://old.example/*"]))
    (-> (expect (:script/run-at derived))
        (.toBe "document-start"))
    (-> (expect (:script/inject derived))
        (.toEqual ["scittle://reagent.js"]))))

;; parse-scripts tests

(defn- test-parse-derives-fields-when-extractor-provided []
  (let [code "^{:epupp/script-name \"derived.cljs\"\n  :epupp/auto-run-match \"https://example.com/*\"}\n(ns derived)"
        js-scripts #js [#js {:id "script-1"
                             :code code
                             :enabled false
                             :created "2026-01-01T00:00:00.000Z"
                             :modified "2026-01-02T00:00:00.000Z"
                             :builtin false}]
        scripts (script-utils/parse-scripts js-scripts {:extract-manifest mp/extract-manifest})
        script (first scripts)]
    (-> (expect (:script/name script))
        (.toBe "derived.cljs"))
    (-> (expect (:script/match script))
        (.toEqual ["https://example.com/*"]))
    (-> (expect (:script/enabled script))
        (.toBe false))))

;; script->js tests

(defn- test-script-js-stores-only-primary-fields []
  (let [script {:script/id "script-1"
                :script/name "derived.cljs"
                :script/description "Example"
                :script/match ["https://example.com/*"]
                :script/code "(ns derived)"
                :script/enabled true
                :script/created "2026-01-01T00:00:00.000Z"
                :script/modified "2026-01-02T00:00:00.000Z"
                :script/run-at "document-end"
                :script/inject ["scittle://reagent.js"]
                :script/builtin? true}
        js-script (script-utils/script->js script)
        keys (js/Object.keys js-script)]
    ;; Primary fields + runAt + match (needed by early injection loader) + special + webInstallerScan
    (-> (expect (.-length keys))
        (.toBe 11))
    (-> (expect (.includes keys "id"))
        (.toBe true))
    (-> (expect (.includes keys "code"))
        (.toBe true))
    (-> (expect (.includes keys "enabled"))
        (.toBe true))
    (-> (expect (.includes keys "created"))
        (.toBe true))
    (-> (expect (.includes keys "modified"))
        (.toBe true))
    (-> (expect (.includes keys "builtin"))
        (.toBe true))
    (-> (expect (.includes keys "alwaysEnabled"))
        (.toBe true))
    (-> (expect (.includes keys "special"))
        (.toBe true))
    (-> (expect (.includes keys "webInstallerScan"))
        (.toBe true))
    ;; runAt and match stored for early injection loader
    (-> (expect (.includes keys "runAt"))
        (.toBe true))
    (-> (expect (.includes keys "match"))
        (.toBe true))
    (-> (expect (aget js-script "runAt"))
        (.toBe "document-end"))
    (-> (expect (aget js-script "match"))
        (.toEqual #js ["https://example.com/*"]))
    ;; Derived fields NOT stored (re-derived from manifest on load)
    (-> (expect (aget js-script "name"))
        (.toBeUndefined))
    (-> (expect (aget js-script "description"))
        (.toBeUndefined))
    (-> (expect (aget js-script "inject"))
        (.toBeUndefined))
    (-> (expect (.-builtin js-script))
        (.toBe true))))

;; ============================================================
;; Test Registration
;; ============================================================

(describe "validate-script-name"
          (fn []
            (test "accepts valid names" test-validate-accepts-valid-names)
            (test "rejects reserved namespace" test-validate-rejects-reserved-namespace)
            (test "rejects leading slash" test-validate-rejects-leading-slash)
            (test "rejects dot-slash and dot-dot-slash" test-validate-rejects-dot-slash-and-dot-dot-slash)
            (test "property: valid names are accepted" test-validate-property-valid-names-accepted)
            (test "property: reserved namespace is rejected" test-validate-property-reserved-namespace-rejected)
            (test "property: leading slash is rejected" test-validate-property-leading-slash-rejected)
            (test "property: path traversal is rejected" test-validate-property-path-traversal-rejected)))

(describe "derive-script-fields"
          (fn []
            (test "derives fields from manifest" test-derive-fields-from-manifest)
            (test "manifest without auto-run-match clears match" test-derive-manifest-without-auto-run-clears-match)
            (test "nil manifest preserves existing fields" test-derive-nil-manifest-preserves-existing-fields)))

;; normalize-and-merge-script tests

(defn- test-normalize-merge-new-script-with-manifest []
  (let [code "^{:epupp/script-name \"My Script\"\n  :epupp/auto-run-match \"https://example.com/*\"\n  :epupp/description \"Test\"\n  :epupp/run-at \"document-end\"\n  :epupp/inject \"scittle://reagent.js\"}\n(ns my-script)"
        manifest (mp/extract-manifest code)
        script {:script/id "script-1" :script/code code}
        result (script-utils/normalize-and-merge-script
                 script nil manifest
                 {:now-iso "2026-01-01T00:00:00.000Z"})
        s (:script result)]
    (-> (expect (:error result)) (.toBeUndefined))
    (-> (expect (:script/name s)) (.toBe "my_script.cljs"))
    (-> (expect (:script/match s)) (.toEqual ["https://example.com/*"]))
    (-> (expect (:script/description s)) (.toBe "Test"))
    (-> (expect (:script/run-at s)) (.toBe "document-end"))
    (-> (expect (:script/inject s)) (.toEqual ["scittle://reagent.js"]))
    (-> (expect (:script/enabled s)) (.toBe false))
    (-> (expect (:script/created s)) (.toBe "2026-01-01T00:00:00.000Z"))
    (-> (expect (:script/modified s)) (.toBe "2026-01-01T00:00:00.000Z"))))

(defn- test-normalize-merge-new-script-without-manifest []
  (let [script {:script/id "script-1"
                :script/code "(ns no-manifest)"
                :script/name "my-test.cljs"
                :script/match ["https://example.com/*"]
                :script/run-at "document-start"}
        result (script-utils/normalize-and-merge-script
                 script nil nil
                 {:now-iso "2026-01-01T00:00:00.000Z"})
        s (:script result)]
    (-> (expect (:error result)) (.toBeUndefined))
    (-> (expect (:script/name s)) (.toBe "my_test.cljs"))
    (-> (expect (:script/match s)) (.toEqual ["https://example.com/*"]))
    (-> (expect (:script/run-at s)) (.toBe "document-start"))
    (-> (expect (:script/enabled s)) (.toBe false))
    (-> (expect (:script/created s)) (.toBe "2026-01-01T00:00:00.000Z"))))

(defn- test-normalize-merge-update-preserves-enabled []
  (let [existing {:script/id "script-1"
                  :script/name "test.cljs"
                  :script/code "(ns old)"
                  :script/enabled true
                  :script/match ["https://example.com/*"]
                  :script/created "2025-01-01T00:00:00.000Z"
                  :script/modified "2025-06-01T00:00:00.000Z"}
        code "^{:epupp/script-name \"test.cljs\"\n  :epupp/auto-run-match \"https://example.com/*\"}\n(ns test)"
        manifest (mp/extract-manifest code)
        script {:script/id "script-1" :script/code code}
        result (script-utils/normalize-and-merge-script
                 script existing manifest
                 {:now-iso "2026-01-01T00:00:00.000Z"})
        s (:script result)]
    (-> (expect (:script/enabled s)) (.toBe true))
    (-> (expect (:script/created s)) (.toBe "2025-01-01T00:00:00.000Z"))
    (-> (expect (:script/modified s)) (.toBe "2026-01-01T00:00:00.000Z"))))

(defn- test-normalize-merge-manifest-revokes-auto-run []
  (let [existing {:script/id "script-1"
                  :script/name "test.cljs"
                  :script/code "(ns old)"
                  :script/enabled true
                  :script/match ["https://example.com/*"]
                  :script/created "2025-01-01T00:00:00.000Z"}
        code "^{:epupp/script-name \"test.cljs\"}\n(ns test)"
        manifest (mp/extract-manifest code)
        script {:script/id "script-1" :script/code code}
        result (script-utils/normalize-and-merge-script
                 script existing manifest
                 {:now-iso "2026-01-01T00:00:00.000Z"})
        s (:script result)]
    (-> (expect (:script/match s)) (.toEqual []))
    (-> (expect (:script/enabled s)) (.toBe false))))

(defn- test-normalize-merge-name-validation-error []
  (let [script {:script/id "script-1"
                :script/code "(ns test)"
                :script/name "epupp/reserved.cljs"}
        result (script-utils/normalize-and-merge-script
                 script nil nil
                 {:now-iso "2026-01-01T00:00:00.000Z"})]
    (-> (expect (:error result)) (.toContain "reserved namespace"))))

(defn- test-normalize-merge-builtin-bypasses-normalization []
  (let [script {:script/id "builtin-1"
                :script/code "(ns builtin)"
                :script/name "Builtin Name"
                :script/builtin? true
                :script/match ["<all_urls>"]}
        result (script-utils/normalize-and-merge-script
                 script nil nil
                 {:is-builtin? true
                  :now-iso "2026-01-01T00:00:00.000Z"})
        s (:script result)]
    (-> (expect (:script/name s)) (.toBe "Builtin Name"))))

(defn- test-normalize-merge-always-enabled []
  (let [script {:script/id "script-1"
                :script/code "(ns test)"
                :script/name "test.cljs"
                :script/always-enabled? true
                :script/match []}
        result (script-utils/normalize-and-merge-script
                 script nil nil
                 {:now-iso "2026-01-01T00:00:00.000Z"})
        s (:script result)]
    (-> (expect (:script/enabled s)) (.toBe true))))

(defn- test-normalize-merge-no-manifest-falls-back-to-existing-match []
  (let [existing {:script/id "script-1"
                  :script/name "test.cljs"
                  :script/code "(ns old)"
                  :script/enabled true
                  :script/match ["https://example.com/*"]
                  :script/created "2025-01-01T00:00:00.000Z"}
        script {:script/id "script-1"
                :script/code "(ns updated-code)"}
        result (script-utils/normalize-and-merge-script
                 script existing nil
                 {:now-iso "2026-01-01T00:00:00.000Z"})
        s (:script result)]
    (-> (expect (:script/match s)) (.toEqual ["https://example.com/*"]))
    (-> (expect (:script/enabled s)) (.toBe true))))

(describe "normalize-and-merge-script"
          (fn []
            (test "new script with manifest derives all fields" test-normalize-merge-new-script-with-manifest)
            (test "new script without manifest uses own fields" test-normalize-merge-new-script-without-manifest)
            (test "update preserves existing enabled state" test-normalize-merge-update-preserves-enabled)
            (test "manifest without auto-run revokes and disables" test-normalize-merge-manifest-revokes-auto-run)
            (test "returns error on invalid name" test-normalize-merge-name-validation-error)
            (test "builtin bypasses name normalization" test-normalize-merge-builtin-bypasses-normalization)
            (test "always-enabled stays enabled" test-normalize-merge-always-enabled)
            (test "no manifest falls back to existing match" test-normalize-merge-no-manifest-falls-back-to-existing-match)))

(describe "parse-scripts"
          (fn []
            (test "derives fields when extractor is provided" test-parse-derives-fields-when-extractor-provided)))

(defn- test-script-js-roundtrips-special-flags []
  (let [script {:script/id "installer-1"
                :script/code "(ns installer)"
                :script/enabled false
                :script/created "2026-01-01T00:00:00.000Z"
                :script/modified "2026-01-02T00:00:00.000Z"
                :script/builtin? true
                :script/special? true
                :script/web-installer-scan true}
        js-script (script-utils/script->js script)
        ;; Roundtrip through parse-scripts
        parsed (first (script-utils/parse-scripts #js [js-script]))]
    ;; Verify JS object has the flags
    (-> (expect (.-special js-script))
        (.toBe true))
    (-> (expect (.-webInstallerScan js-script))
        (.toBe true))
    ;; Verify roundtrip preserves flags
    (-> (expect (:script/special? parsed))
        (.toBe true))
    (-> (expect (:script/web-installer-scan parsed))
        (.toBe true))))

(defn- test-script-js-roundtrips-without-special-flags []
  (let [script {:script/id "regular-1"
                :script/code "(ns regular)"
                :script/enabled true
                :script/created "2026-01-01T00:00:00.000Z"
                :script/modified "2026-01-02T00:00:00.000Z"
                :script/builtin? false}
        js-script (script-utils/script->js script)
        parsed (first (script-utils/parse-scripts #js [js-script]))]
    ;; Verify JS object has nil/undefined for flags
    (-> (expect (.-special js-script))
        (.toBeFalsy))
    (-> (expect (.-webInstallerScan js-script))
        (.toBeFalsy))
    ;; Verify roundtrip: flags should not be set
    (-> (expect (:script/special? parsed))
        (.toBeUndefined))
    (-> (expect (:script/web-installer-scan parsed))
        (.toBeUndefined))))

(describe "script->js"
          (fn []
            (test "stores only primary fields, not derived" test-script-js-stores-only-primary-fields)
            (test "roundtrips special flags through parse-scripts" test-script-js-roundtrips-special-flags)
            (test "roundtrips scripts without special flags" test-script-js-roundtrips-without-special-flags)))

;; diff-scripts tests

(defn- test-diff-scripts-detects-added []
  (let [old-scripts [{:script/name "existing.cljs" :script/code "(ns existing)"}]
        new-scripts [{:script/name "existing.cljs" :script/code "(ns existing)"}
                     {:script/name "new.cljs" :script/code "(ns new)"}]
        diff (script-utils/diff-scripts old-scripts new-scripts)]
    (-> (expect (:added diff))
        (.toEqual ["new.cljs"]))
    (-> (expect (:modified diff))
        (.toEqual []))
    (-> (expect (:removed diff))
        (.toEqual []))))

(defn- test-diff-scripts-detects-removed []
  (let [old-scripts [{:script/name "existing.cljs" :script/code "(ns existing)"}
                     {:script/name "removed.cljs" :script/code "(ns removed)"}]
        new-scripts [{:script/name "existing.cljs" :script/code "(ns existing)"}]
        diff (script-utils/diff-scripts old-scripts new-scripts)]
    (-> (expect (:added diff))
        (.toEqual []))
    (-> (expect (:modified diff))
        (.toEqual []))
    (-> (expect (:removed diff))
        (.toEqual ["removed.cljs"]))))

(defn- test-diff-scripts-detects-modified-code []
  (let [old-scripts [{:script/name "changed.cljs" :script/code "(ns old)"}]
        new-scripts [{:script/name "changed.cljs" :script/code "(ns new)"}]
        diff (script-utils/diff-scripts old-scripts new-scripts)]
    (-> (expect (:added diff))
        (.toEqual []))
    (-> (expect (:modified diff))
        (.toEqual ["changed.cljs"]))
    (-> (expect (:removed diff))
        (.toEqual []))))

(defn- test-diff-scripts-no-changes []
  (let [old-scripts [{:script/name "unchanged.cljs" :script/code "(ns unchanged)"}]
        new-scripts [{:script/name "unchanged.cljs" :script/code "(ns unchanged)"}]
        diff (script-utils/diff-scripts old-scripts new-scripts)]
    (-> (expect (:added diff))
        (.toEqual []))
    (-> (expect (:modified diff))
        (.toEqual []))
    (-> (expect (:removed diff))
        (.toEqual []))))

(defn- test-diff-scripts-multiple-changes []
  (let [old-scripts [{:script/name "a.cljs" :script/code "(ns a)"}
                     {:script/name "b.cljs" :script/code "(ns b)"}
                     {:script/name "c.cljs" :script/code "(ns c-old)"}]
        new-scripts [{:script/name "a.cljs" :script/code "(ns a)"}
                     {:script/name "c.cljs" :script/code "(ns c-new)"}
                     {:script/name "d.cljs" :script/code "(ns d)"}]
        diff (script-utils/diff-scripts old-scripts new-scripts)]
    (-> (expect (:added diff))
        (.toEqual ["d.cljs"]))
    (-> (expect (:modified diff))
        (.toEqual ["c.cljs"]))
    (-> (expect (:removed diff))
        (.toEqual ["b.cljs"]))))

(defn- test-diff-scripts-code-vs-metadata-change []
  (let [old-scripts [{:script/name "test.cljs"
                      :script/code "(ns test)"
                      :script/description "Old description"}]
        new-scripts [{:script/name "test.cljs"
                      :script/code "(ns test)"
                      :script/description "New description"}]
        diff (script-utils/diff-scripts old-scripts new-scripts)]
    ;; Only code changes count as modified
    (-> (expect (:modified diff))
        (.toEqual []))))

(describe "Script list diffing"
  (fn []
    (test "Added scripts detected" test-diff-scripts-detects-added)
    (test "Removed scripts detected" test-diff-scripts-detects-removed)
    (test "Modified scripts detected (code changed)" test-diff-scripts-detects-modified-code)
    (test "No changes → empty diff" test-diff-scripts-no-changes)
    (test "Multiple simultaneous changes" test-diff-scripts-multiple-changes)
    (test "Code change vs metadata-only change" test-diff-scripts-code-vs-metadata-change)))

;; filter-visible-scripts tests

(defn- test-filter-visible-include-hidden-returns-all []
  (let [scripts [{:script/name "user.cljs" :script/builtin? false}
                 {:script/name "builtin.cljs" :script/builtin? true}]
        filtered (script-utils/filter-visible-scripts scripts true)]
    (-> (expect (count filtered))
        (.toBe 2))))

(defn- test-filter-visible-exclude-hidden-filters-builtins []
  (let [scripts [{:script/name "user.cljs" :script/builtin? false}
                 {:script/name "builtin.cljs" :script/builtin? true}]
        filtered (script-utils/filter-visible-scripts scripts false)]
    (-> (expect (count filtered))
        (.toBe 1))
    (-> (expect (:script/name (first filtered)))
        (.toBe "user.cljs"))))

(defn- test-filter-visible-empty-list []
  (let [filtered (script-utils/filter-visible-scripts [] false)]
    (-> (expect filtered)
        (.toEqual []))))

(defn- test-filter-visible-only-builtins-with-hidden-false []
  (let [scripts [{:script/name "builtin1.cljs" :script/builtin? true}
                 {:script/name "builtin2.cljs" :script/builtin? true}]
        filtered (script-utils/filter-visible-scripts scripts false)]
    (-> (expect filtered)
        (.toEqual []))))

(defn- test-filter-visible-mixed-scripts []
  (let [scripts [{:script/name "user1.cljs" :script/builtin? false}
                 {:script/name "builtin.cljs" :script/builtin? true}
                 {:script/name "user2.cljs" :script/builtin? false}]
        filtered (script-utils/filter-visible-scripts scripts false)]
    (-> (expect (count filtered))
        (.toBe 2))
    (-> (expect (mapv :script/name filtered))
        (.toEqual ["user1.cljs" "user2.cljs"]))))

;; ============================================================
;; special-script? predicate tests
;; ============================================================

(defn- test-special-script-true-when-flag-set []
  (let [script {:script/name "installer.cljs" :script/special? true}]
    (-> (expect (script-utils/special-script? script))
        (.toBe true))))

(defn- test-special-script-false-when-flag-absent []
  (let [script {:script/name "regular.cljs"}]
    (-> (expect (script-utils/special-script? script))
        (.toBe false))))

(defn- test-special-script-false-when-flag-false []
  (let [script {:script/name "regular.cljs" :script/special? false}]
    (-> (expect (script-utils/special-script? script))
        (.toBe false))))

(defn- test-special-script-false-when-flag-nil []
  (let [script {:script/name "regular.cljs" :script/special? nil}]
    (-> (expect (script-utils/special-script? script))
        (.toBe false))))

(describe "special-script? predicate"
  (fn []
    (test "returns true when :script/special? is true" test-special-script-true-when-flag-set)
    (test "returns false when :script/special? is absent" test-special-script-false-when-flag-absent)
    (test "returns false when :script/special? is false" test-special-script-false-when-flag-false)
    (test "returns false when :script/special? is nil" test-special-script-false-when-flag-nil)))

(describe "Script visibility filtering"
  (fn []
    (test "include-hidden? true → returns all scripts" test-filter-visible-include-hidden-returns-all)
    (test "include-hidden? false → filters out built-ins" test-filter-visible-exclude-hidden-filters-builtins)
    (test "Empty list → returns empty" test-filter-visible-empty-list)
    (test "Only built-ins with hidden=false → returns empty" test-filter-visible-only-builtins-with-hidden-false)
    (test "Mixed scripts with hidden=false → returns only user scripts" test-filter-visible-mixed-scripts)))


;; ============================================================
;; Name conflict detection tests
;; ============================================================

(defn- test-detect-name-conflict-new-script-unique-name []
  (let [scripts [{:script/name "existing.cljs"}
                 {:script/name "another.cljs"}]
        conflict (script-utils/detect-name-conflict scripts "unique_name" nil)]
    (-> (expect conflict)
        (.toBe nil))))

(defn- test-detect-name-conflict-new-script-existing-name []
  (let [scripts [{:script/name "existing.cljs"}
                 {:script/name "another.cljs"}]
        conflict (script-utils/detect-name-conflict scripts "existing.cljs" nil)]
    (-> (expect conflict)
        (.not.toBe nil))
    (-> (expect (:script/name conflict))
        (.toBe "existing.cljs"))))

(defn- test-detect-name-conflict-rename-to-unique []
  (let [scripts [{:script/name "current.cljs"}
                 {:script/name "other.cljs"}]
        ;; Editing current.cljs, renaming to new_name.cljs
        conflict (script-utils/detect-name-conflict scripts "New Name" "current.cljs")]
    (-> (expect conflict)
        (.toBe nil))))

(defn- test-detect-name-conflict-rename-to-existing []
  (let [scripts [{:script/name "current.cljs"}
                 {:script/name "other.cljs"}]
        ;; Editing current.cljs, trying to rename to other.cljs
        conflict (script-utils/detect-name-conflict scripts "other.cljs" "current.cljs")]
    (-> (expect conflict)
        (.not.toBe nil))
    (-> (expect (:script/name conflict))
        (.toBe "other.cljs"))))

(defn- test-detect-name-conflict-rename-to-same-name []
  (let [scripts [{:script/name "current.cljs"}
                 {:script/name "other.cljs"}]
        ;; Editing current.cljs, keeping the same name
        conflict (script-utils/detect-name-conflict scripts "current.cljs" "current.cljs")]
    (-> (expect conflict)
        (.toBe nil))))

(defn- test-detect-name-conflict-case-insensitive []
  (let [scripts [{:script/name "my_script.cljs"}
                 {:script/name "other.cljs"}]
        ;; "My Script" normalizes to "my_script.cljs" - should conflict
        conflict (script-utils/detect-name-conflict scripts "My Script" nil)]
    (-> (expect conflict)
        (.not.toBe nil))
    (-> (expect (:script/name conflict))
        (.toBe "my_script.cljs"))))

(defn- test-detect-name-conflict-normalization-with-spaces []
  (let [scripts [{:script/name "my_cool_script.cljs"}]
        ;; "My Cool Script" should normalize to "my_cool_script.cljs"
        conflict (script-utils/detect-name-conflict scripts "My Cool Script" nil)]
    (-> (expect conflict)
        (.not.toBe nil))))

(defn- test-detect-name-conflict-empty-scripts-list []
  (let [conflict (script-utils/detect-name-conflict [] "any_name" nil)]
    (-> (expect conflict)
        (.toBe nil))))

(describe "Name conflict detection"
  (fn []
    (test "new script with unique name → no conflict" test-detect-name-conflict-new-script-unique-name)
    (test "new script with existing name → conflict" test-detect-name-conflict-new-script-existing-name)
    (test "rename to unique name → no conflict" test-detect-name-conflict-rename-to-unique)
    (test "rename to existing name → conflict" test-detect-name-conflict-rename-to-existing)
    (test "rename to same name → no conflict" test-detect-name-conflict-rename-to-same-name)
    (test "case insensitive matching → conflict" test-detect-name-conflict-case-insensitive)
    (test "normalization with spaces → conflict" test-detect-name-conflict-normalization-with-spaces)
    (test "empty scripts list → no conflict" test-detect-name-conflict-empty-scripts-list)))

;; ============================================================
;; detect-browser-type tests
;; ============================================================

(defn- test-detect-browser-type-firefox []
  (.stubGlobal vi "navigator"
               #js {:userAgent "Mozilla/5.0 (X11; Linux x86_64; rv:109.0) Gecko/20100101 Firefox/115.0"})
  (-> (expect (script-utils/detect-browser-type))
      (.toBe :firefox)))

(defn- test-detect-browser-type-brave []
  (.stubGlobal vi "navigator"
               #js {:userAgent "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                    :brave #js {}})
  (-> (expect (script-utils/detect-browser-type))
      (.toBe :brave)))

(defn- test-detect-browser-type-edge []
  (.stubGlobal vi "navigator"
               #js {:userAgent "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 Edg/120.0.0.0"})
  (-> (expect (script-utils/detect-browser-type))
      (.toBe :edge)))

(defn- test-detect-browser-type-safari []
  (.stubGlobal vi "navigator"
               #js {:userAgent "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15"})
  (-> (expect (script-utils/detect-browser-type))
      (.toBe :safari)))

(defn- test-detect-browser-type-chrome []
  (.stubGlobal vi "navigator"
               #js {:userAgent "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"})
  (-> (expect (script-utils/detect-browser-type))
      (.toBe :chrome)))

(defn- test-detect-browser-type-firefox-priority-over-chrome []
  ;; Firefox UA does not contain "Chrome", but test that priority order is correct
  (.stubGlobal vi "navigator"
               #js {:userAgent "Mozilla/5.0 (X11; Linux x86_64; rv:109.0) Gecko/20100101 Firefox/115.0"
                    :brave nil})
  (-> (expect (script-utils/detect-browser-type))
      (.toBe :firefox)))

(describe "detect-browser-type"
  (fn []
    (afterEach (fn [] (.unstubAllGlobals vi)))
    (test "detects Firefox" test-detect-browser-type-firefox)
    (test "detects Brave" test-detect-browser-type-brave)
    (test "detects Edge" test-detect-browser-type-edge)
    (test "detects Safari" test-detect-browser-type-safari)
    (test "detects Chrome (default)" test-detect-browser-type-chrome)
    (test "Firefox has priority over chrome compat" test-detect-browser-type-firefox-priority-over-chrome)))

;; ============================================================
;; check-page-scriptability tests
;; ============================================================

(defn- test-scriptability-nil-url []
  (let [result (script-utils/check-page-scriptability nil :chrome)]
    (-> (expect (:scriptable? result)) (.toBe false))
    (-> (expect (:message result)) (.toContain "No URL"))))

(defn- test-scriptability-empty-url []
  (let [result (script-utils/check-page-scriptability "" :chrome)]
    (-> (expect (:scriptable? result)) (.toBe false))
    (-> (expect (:message result)) (.toContain "No URL"))))

(defn- test-scriptability-normal-https-url []
  (let [result (script-utils/check-page-scriptability "https://example.com/page" :chrome)]
    (-> (expect (:scriptable? result)) (.toBe true))
    (-> (expect (:message result)) (.toBeUndefined))))

(defn- test-scriptability-chrome-scheme []
  (let [result (script-utils/check-page-scriptability "chrome://settings" :chrome)]
    (-> (expect (:scriptable? result)) (.toBe false))
    (-> (expect (:message result)) (.toContain "chrome"))))

(defn- test-scriptability-about-scheme []
  (let [result (script-utils/check-page-scriptability "about:blank" :chrome)]
    (-> (expect (:scriptable? result)) (.toBe false))
    (-> (expect (:message result)) (.toContain "about"))))

(defn- test-scriptability-devtools-scheme []
  (let [result (script-utils/check-page-scriptability "devtools://devtools/bundled/inspector.html" :chrome)]
    (-> (expect (:scriptable? result)) (.toBe false))
    (-> (expect (:message result)) (.toContain "devtools"))))

(defn- test-scriptability-moz-extension-scheme []
  (let [result (script-utils/check-page-scriptability "moz-extension://abc-123/popup.html" :firefox)]
    (-> (expect (:scriptable? result)) (.toBe false))))

(defn- test-scriptability-chrome-webstore-blocked []
  (let [result (script-utils/check-page-scriptability
                "https://chrome.google.com/webstore/detail/some-ext" :chrome)]
    (-> (expect (:scriptable? result)) (.toBe false))
    (-> (expect (:message result)) (.toContain "stores"))))

(defn- test-scriptability-chromewebstore-blocked []
  (let [result (script-utils/check-page-scriptability
                "https://chromewebstore.google.com/detail/some-ext" :chrome)]
    (-> (expect (:scriptable? result)) (.toBe false))
    (-> (expect (:message result)) (.toContain "stores"))))

(defn- test-scriptability-firefox-addons-blocked []
  (let [result (script-utils/check-page-scriptability
                "https://addons.mozilla.org/en-US/firefox/addon/some-addon" :firefox)]
    (-> (expect (:scriptable? result)) (.toBe false))
    (-> (expect (:message result)) (.toContain "stores"))))

(defn- test-scriptability-edge-addons-blocked []
  (let [result (script-utils/check-page-scriptability
                "https://microsoftedge.microsoft.com/addons/detail/some-ext" :edge)]
    (-> (expect (:scriptable? result)) (.toBe false))
    (-> (expect (:message result)) (.toContain "stores"))))

(defn- test-scriptability-firefox-domain-not-blocked-for-chrome []
  (let [result (script-utils/check-page-scriptability
                "https://addons.mozilla.org/en-US/firefox" :chrome)]
    (-> (expect (:scriptable? result)) (.toBe true))))

(defn- test-scriptability-edge-domain-not-blocked-for-firefox []
  (let [result (script-utils/check-page-scriptability
                "https://microsoftedge.microsoft.com/addons" :firefox)]
    (-> (expect (:scriptable? result)) (.toBe true))))

(describe "check-page-scriptability"
  (fn []
    (test "nil URL is not scriptable" test-scriptability-nil-url)
    (test "empty URL is not scriptable" test-scriptability-empty-url)
    (test "normal HTTPS URL is scriptable" test-scriptability-normal-https-url)
    (test "chrome: scheme is blocked" test-scriptability-chrome-scheme)
    (test "about: scheme is blocked" test-scriptability-about-scheme)
    (test "devtools: scheme is blocked" test-scriptability-devtools-scheme)
    (test "moz-extension: scheme is blocked" test-scriptability-moz-extension-scheme)
    (test "Chrome Web Store (old URL) blocked for Chrome" test-scriptability-chrome-webstore-blocked)
    (test "Chrome Web Store (new URL) blocked for Chrome" test-scriptability-chromewebstore-blocked)
    (test "Firefox Add-ons blocked for Firefox" test-scriptability-firefox-addons-blocked)
    (test "Edge Add-ons blocked for Edge" test-scriptability-edge-addons-blocked)
    (test "Firefox domains NOT blocked for Chrome" test-scriptability-firefox-domain-not-blocked-for-chrome)
    (test "Edge domains NOT blocked for Firefox" test-scriptability-edge-domain-not-blocked-for-firefox)))
