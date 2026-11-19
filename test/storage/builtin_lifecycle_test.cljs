(ns storage.builtin-lifecycle-test
  "Tests for built-in script reconciliation and update detection."
  (:require ["vitest" :refer [describe test expect]]
            [storage :as storage]))

;; ============================================================
;; Helper Functions
;; ============================================================

(defn determine-enabled-state
  "Determine the enabled state for a script.
   Used here to test built-in enabled state preservation."
  [{:keys [has-auto-run? existing-enabled is-new? always-enabled?]}]
  (cond
    always-enabled? true
    (not has-auto-run?) false
    (not is-new?) existing-enabled
    :else false))

;; ============================================================
;; Built-in reconciliation tests
;; ============================================================

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

(defn- test-bundled-builtin-ids-include-internal-helpers []
  (let [ids (storage/bundled-builtin-ids)]
    (-> (expect ids) (.toContain "epupp-builtin-internal-helpers"))
    (-> (expect ids) (.toContain "epupp-builtin-storage"))
    (-> (expect ids) (.toContain "epupp-builtin-tools"))))

;; ============================================================
;; Built-in update detection tests
;; ============================================================

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

(defn- test-builtin-special-flag-differs-needs-update []
  (let [existing {:script/id "builtin-1"
                  :script/code "(ns test)"
                  :script/name "test.cljs"}
        desired (assoc existing :script/special? true)]
    (-> (expect (storage/builtin-update-needed? existing desired))
        (.toBe true))))

(defn- test-builtin-web-installer-scan-differs-needs-update []
  (let [existing {:script/id "builtin-1"
                  :script/code "(ns test)"
                  :script/name "test.cljs"}
        desired (assoc existing :script/web-installer-scan true)]
    (-> (expect (storage/builtin-update-needed? existing desired))
        (.toBe true))))

;; ============================================================
;; Import tests
;; ============================================================

(defn- test-import-replaces-user-scripts-keeps-builtins []
  (let [scripts [{:script/id "epupp-builtin-tools" :script/builtin? true}
                 {:script/id "script-old" :script/builtin? false}]
        imported [{:script/id "script-new" :script/builtin? false}]]
    (-> (expect (mapv :script/id (storage/merge-imported-scripts scripts imported)))
        (.toEqual ["script-new" "epupp-builtin-tools"]))))

(defn- test-import-skips-builtin-ids []
  (let [scripts [{:script/id "epupp-builtin-tools" :script/builtin? true :script/code "bundled"}]
        imported [{:script/id "epupp-builtin-tools" :script/code "imported"}
                  {:script/id "script-new"}]
        merged (storage/merge-imported-scripts scripts imported)]
    (-> (expect (mapv :script/id merged))
        (.toEqual ["script-new" "epupp-builtin-tools"]))
    (-> (expect (:script/code (last merged)))
        (.toBe "bundled"))))

;; ============================================================
;; Test Registration
;; ============================================================

(describe "built-in reconciliation"
          (fn []
            (test "removes stale built-ins" test-removes-stale-builtins)
            (test "existing built-in preserves enabled state" test-existing-builtin-preserves-enabled-state)
            (test "bundled built-in ids include internal helpers, storage, and tools" test-bundled-builtin-ids-include-internal-helpers)
            (test "import replaces user scripts and keeps built-ins" test-import-replaces-user-scripts-keeps-builtins)
            (test "import skips entries with built-in ids" test-import-skips-builtin-ids)))

(describe "Built-in script update detection"
          (fn []
            (test "Identical scripts → no update needed" test-builtin-identical-no-update)
            (test "Code differs → update needed" test-builtin-code-differs-needs-update)
            (test "Name differs → update needed" test-builtin-name-differs-needs-update)
            (test "Match patterns differ → update needed" test-builtin-match-differs-needs-update)
            (test "Description differs → update needed" test-builtin-description-differs-needs-update)
            (test "Run-at differs → update needed" test-builtin-run-at-differs-needs-update)
            (test "Inject differs → update needed" test-builtin-inject-differs-needs-update)
            (test "Nil existing (new built-in) → update needed" test-builtin-nil-existing-needs-update)
            (test "Special flag differs → update needed" test-builtin-special-flag-differs-needs-update)
            (test "Web-installer-scan flag differs → update needed" test-builtin-web-installer-scan-differs-needs-update)))
