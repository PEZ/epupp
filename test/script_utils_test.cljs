(ns script-utils-test
  "Unit and property-style tests for script name validation."
  (:require ["vitest" :refer [describe test expect]]
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

(defn- test-script-js-emits-fields-for-early-injection-loader []
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
    ;; Core fields + runAt + match + alwaysEnabled for early loader
    (-> (expect (.-length keys))
        (.toBe 9))
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
    ;; Early loader needs runAt and match
    (-> (expect (.includes keys "runAt"))
        (.toBe true))
    (-> (expect (.includes keys "match"))
        (.toBe true))
    (-> (expect (aget js-script "runAt"))
        (.toBe "document-end"))
    (-> (expect (aget js-script "match"))
        (.toEqual #js ["https://example.com/*"]))
    ;; UI-only fields are still excluded
    (-> (expect (aget js-script "name"))
        (.toBeUndefined))
    (-> (expect (aget js-script "description"))
        (.toBeUndefined))
    (-> (expect (aget js-script "inject"))
        (.toBeUndefined))
    (-> (expect (.-builtin js-script))
        (.toBe true))
    (-> (expect (.includes keys "alwaysEnabled"))
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

(describe "parse-scripts"
          (fn []
            (test "derives fields when extractor is provided" test-parse-derives-fields-when-extractor-provided)))

(describe "script->js"
          (fn []
            (test "emits fields needed for early injection loader" test-script-js-emits-fields-for-early-injection-loader)))

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
