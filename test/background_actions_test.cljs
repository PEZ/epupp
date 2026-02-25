(ns background-actions-test
  "Tests for background FS action handlers - pure decision logic"
  (:require ["vitest" :refer [describe test expect]]
            [background-actions :as bg-actions]
            [background-actions.repl-fs-actions :as repl-fs-actions]))

;; ============================================================
;; Test Fixtures
;; ============================================================

(def base-script
  {:script/id "script-123"
   :script/name "test.cljs"
   :script/description "Test script"
   :script/match ["*://example.com/*"]
   :script/code "(println \"hello\")"
   :script/enabled true
   :script/created "2026-01-01T00:00:00.000Z"
   :script/modified "2026-01-01T00:00:00.000Z"
   :script/run-at "document-idle"
   :script/inject []})

(def builtin-script
  (assoc base-script
         :script/id "script-builtin-1"
         :script/name "gist-installer.cljs"
         :script/builtin? true))

(def initial-state
  {:storage/scripts [base-script]
   :storage/granted-origins []})

(def uf-data {:system/now 1737100000000})

;; ============================================================
;; Rename Script Tests
;; ============================================================

(defn- test-rename-rejects-when-source-script-not-found []
  (let [result (bg-actions/handle-action initial-state uf-data
                 [:fs/ax.rename-script "nonexistent.cljs" "new-name.cljs"])
        error-response (some #(when (= :bg/fx.send-response (first %)) (second %)) (:uf/fxs result))]
    (-> (expect error-response)
        (.toBeTruthy))
    (-> (expect (:success error-response))
        (.toBe false))
    (-> (expect (:error error-response))
        (.toContain "Script not found"))))

(defn- test-rename-rejects-when-source-is-builtin-script []
  (let [state {:storage/scripts [builtin-script]}
        result (bg-actions/handle-action state uf-data
                 [:fs/ax.rename-script "gist-installer.cljs" "renamed.cljs"])
        error-response (some #(when (= :bg/fx.send-response (first %)) (second %)) (:uf/fxs result))]
    (-> (expect error-response)
        (.toBeTruthy))
    (-> (expect (:success error-response))
        (.toBe false))
    (-> (expect (:error error-response))
        (.toContain "built-in"))))

(defn- test-rename-rejects-when-target-name-already-exists []
  (let [other-script (assoc base-script :script/id "script-456" :script/name "existing.cljs")
        state {:storage/scripts [base-script other-script]}
        result (bg-actions/handle-action state uf-data
                 [:fs/ax.rename-script "test.cljs" "existing.cljs"])
        error-response (some #(when (= :bg/fx.send-response (first %)) (second %)) (:uf/fxs result))]
    (-> (expect error-response)
        (.toBeTruthy))
    (-> (expect (:success error-response))
        (.toBe false))
    (-> (expect (:error error-response))
        (.toContain "already exists"))))

(defn- test-rename-rejects-reserved-namespace-on-rename []
  (let [result (bg-actions/handle-action initial-state uf-data
                 [:fs/ax.rename-script "test.cljs" "epupp/test.cljs"])
        error-response (some #(when (= :bg/fx.send-response (first %)) (second %)) (:uf/fxs result))]
    (-> (expect error-response)
        (.toBeTruthy))
    (-> (expect (:success error-response))
        (.toBe false))
    (-> (expect (:error error-response))
        (.toContain "reserved namespace"))))

(defn- test-rename-rejects-reserved-namespace-uppercase-on-rename []
  (let [result (bg-actions/handle-action initial-state uf-data
                 [:fs/ax.rename-script "test.cljs" "EPUPP/test.cljs"])
        error-response (some #(when (= :bg/fx.send-response (first %)) (second %)) (:uf/fxs result))]
    (-> (expect error-response)
        (.toBeTruthy))
    (-> (expect (:success error-response))
        (.toBe false))
    (-> (expect (:error error-response))
        (.toContain "reserved namespace"))))

(defn- test-rename-rejects-leading-slash-on-rename []
  (let [result (bg-actions/handle-action initial-state uf-data
                 [:fs/ax.rename-script "test.cljs" "/test.cljs"])
        error-response (some #(when (= :bg/fx.send-response (first %)) (second %)) (:uf/fxs result))]
    (-> (expect error-response)
        (.toBeTruthy))
    (-> (expect (:success error-response))
        (.toBe false))
    (-> (expect (:error error-response))
        (.toContain "start with '/"))))

(defn- test-rename-rejects-path-traversal-on-rename []
  (let [result (bg-actions/handle-action initial-state uf-data
                 [:fs/ax.rename-script "test.cljs" "foo/../bar.cljs"])
        error-response (some #(when (= :bg/fx.send-response (first %)) (second %)) (:uf/fxs result))]
    (-> (expect error-response)
        (.toBeTruthy))
    (-> (expect (:success error-response))
        (.toBe false))
    (-> (expect (:error error-response))
        (.toContain "./' or '../'"))))

(defn- test-rename-allows-rename-when-target-name-is-free []
  (let [result (bg-actions/handle-action initial-state uf-data
                 [:fs/ax.rename-script "test.cljs" "renamed.cljs"])]
    ;; Should have state update
    (-> (expect (:uf/db result))
        (.toBeTruthy))
    ;; New name should be in state
    (-> (expect (-> result :uf/db :storage/scripts first :script/name))
        (.toBe "renamed.cljs"))
    ;; Should have persist effect
    (-> (expect (some #(= :storage/fx.persist! (first %)) (:uf/fxs result)))
        (.toBeTruthy))
    ;; Should have success response
    (-> (expect (some #(and (= :bg/fx.send-response (first %))
                            (-> % second :success)) (:uf/fxs result)))
        (.toBeTruthy))))

(defn- test-rename-updates-modified-timestamp-on-rename []
  (let [result (bg-actions/handle-action initial-state uf-data
                 [:fs/ax.rename-script "test.cljs" "renamed.cljs"])
        modified (-> result :uf/db :storage/scripts first :script/modified)]
    ;; Modified should be updated (not the original value)
    (-> (expect modified)
        (.not.toBe "2026-01-01T00:00:00.000Z"))))

(describe ":fs/ax.rename-script"
          (fn []
            (test "rejects when source script not found" test-rename-rejects-when-source-script-not-found)
            (test "rejects when source is builtin script" test-rename-rejects-when-source-is-builtin-script)
            (test "rejects when target name already exists" test-rename-rejects-when-target-name-already-exists)
            (test "rejects reserved namespace on rename" test-rename-rejects-reserved-namespace-on-rename)
            (test "rejects reserved namespace uppercase on rename" test-rename-rejects-reserved-namespace-uppercase-on-rename)
            (test "rejects leading slash on rename" test-rename-rejects-leading-slash-on-rename)
            (test "rejects path traversal on rename" test-rename-rejects-path-traversal-on-rename)
            (test "allows rename when target name is free" test-rename-allows-rename-when-target-name-is-free)
            (test "updates modified timestamp on rename" test-rename-updates-modified-timestamp-on-rename)))

;; ============================================================
;; Delete Script Tests
;; ============================================================

(defn- test-delete-rejects-when-script-not-found []
  (let [result (bg-actions/handle-action initial-state uf-data
                 [:fs/ax.delete-script "nonexistent.cljs"])
        error-response (some #(when (= :bg/fx.send-response (first %)) (second %)) (:uf/fxs result))]
    (-> (expect error-response)
        (.toBeTruthy))
    (-> (expect (:success error-response))
        (.toBe false))
    (-> (expect (:error error-response))
        (.toContain "Not deleting non-existent file"))))

(defn- test-delete-rejects-when-script-is-builtin []
  (let [state {:storage/scripts [builtin-script]}
        result (bg-actions/handle-action state uf-data
                 [:fs/ax.delete-script "gist-installer.cljs"])
        error-response (some #(when (= :bg/fx.send-response (first %)) (second %)) (:uf/fxs result))]
    (-> (expect error-response)
        (.toBeTruthy))
    (-> (expect (:success error-response))
        (.toBe false))
    (-> (expect (:error error-response))
        (.toContain "built-in"))))

(defn- test-delete-allows-delete-and-removes-from-state []
  (let [result (bg-actions/handle-action initial-state uf-data
                 [:fs/ax.delete-script "test.cljs"])]
    ;; Should have state update
    (-> (expect (:uf/db result))
        (.toBeTruthy))
    ;; Script should be removed
    (-> (expect (count (-> result :uf/db :storage/scripts)))
        (.toBe 0))
    ;; Should have persist effect
    (-> (expect (some #(= :storage/fx.persist! (first %)) (:uf/fxs result)))
        (.toBeTruthy))
    ;; Should have success response
    (-> (expect (some #(and (= :bg/fx.send-response (first %))
                            (-> % second :success)) (:uf/fxs result)))
        (.toBeTruthy))))

(describe ":fs/ax.delete-script"
          (fn []
            (test "rejects when script not found" test-delete-rejects-when-script-not-found)
            (test "rejects when script is builtin" test-delete-rejects-when-script-is-builtin)
            (test "allows delete and removes from state" test-delete-allows-delete-and-removes-from-state)))

;; ============================================================
;; Save Script Tests
;; ============================================================

(defn- test-save-rejects-when-updating-a-builtin-script []
  (let [state {:storage/scripts [builtin-script]}
        updated-script (assoc builtin-script :script/code "(new code)")
        result (bg-actions/handle-action state uf-data
                 [:fs/ax.save-script updated-script])
        error-response (some #(when (= :bg/fx.send-response (first %)) (second %)) (:uf/fxs result))]
    (-> (expect error-response)
        (.toBeTruthy))
    (-> (expect (:success error-response))
        (.toBe false))
    (-> (expect (:error error-response))
        (.toContain "built-in"))))

(defn- test-save-rejects-when-name-exists-and-not-force []
  (let [new-script {:script/id "script-new"
                    :script/name "test.cljs"  ;; Same name as existing
                    :script/code "(new code)"}
        result (bg-actions/handle-action initial-state uf-data
                 [:fs/ax.save-script new-script])
        error-response (some #(when (= :bg/fx.send-response (first %)) (second %)) (:uf/fxs result))]
    (-> (expect error-response)
        (.toBeTruthy))
    (-> (expect (:success error-response))
        (.toBe false))
    (-> (expect (:error error-response))
        (.toContain "already exists"))))

(defn- test-save-force-overwrite-preserves-existing-script-id []
  (let [new-script {:script/id "script-new"  ;; Different ID
                    :script/name "test.cljs" ;; Same name as existing
                    :script/code "(new code)"
                    :script/force? true}
        result (bg-actions/handle-action initial-state uf-data
                 [:fs/ax.save-script new-script])]
    ;; Should have state update
    (-> (expect (:uf/db result))
        (.toBeTruthy))
    ;; Script should be updated, not duplicated
    (-> (expect (count (-> result :uf/db :storage/scripts)))
        (.toBe 1))
    ;; The script should have the ORIGINAL ID (preserved from existing)
    (-> (expect (-> result :uf/db :storage/scripts first :script/id))
        (.toBe "script-123"))
    ;; But with new code
    (-> (expect (-> result :uf/db :storage/scripts first :script/code))
        (.toBe "(new code)"))))

(defn- test-save-allows-create-when-name-is-new []
  (let [new-script {:script/id "script-new"
                    :script/name "brand-new.cljs"
                    :script/code "(new code)"}
        result (bg-actions/handle-action initial-state uf-data
                 [:fs/ax.save-script new-script])]
    ;; Should have state update
    (-> (expect (:uf/db result))
        (.toBeTruthy))
    ;; Should have 2 scripts now
    (-> (expect (count (-> result :uf/db :storage/scripts)))
        (.toBe 2))
    ;; Should have persist and success
    (-> (expect (some #(= :storage/fx.persist! (first %)) (:uf/fxs result)))
        (.toBeTruthy))
    (-> (expect (some #(and (= :bg/fx.send-response (first %))
                            (-> % second :success)) (:uf/fxs result)))
        (.toBeTruthy))))

(defn- test-save-allows-update-when-script-exists-by-id []
  (let [updated-script (assoc base-script :script/code "(updated code)")
        result (bg-actions/handle-action initial-state uf-data
                 [:fs/ax.save-script updated-script])]
    ;; Should have state update
    (-> (expect (:uf/db result))
        (.toBeTruthy))
    ;; Should still have 1 script
    (-> (expect (count (-> result :uf/db :storage/scripts)))
        (.toBe 1))
    ;; Code should be updated
    (-> (expect (-> result :uf/db :storage/scripts first :script/code))
        (.toBe "(updated code)"))
    ;; Should have success response
    (-> (expect (some #(and (= :bg/fx.send-response (first %))
                            (-> % second :success)) (:uf/fxs result)))
        (.toBeTruthy))))

(defn- test-save-allows-overwrite-when-force-flag-set []
  (let [new-script {:script/id "script-new"
                    :script/name "test.cljs"  ;; Same name as existing
                    :script/code "(overwrite code)"
                    :script/force? true}
        result (bg-actions/handle-action initial-state uf-data
                 [:fs/ax.save-script new-script])]
    ;; Should succeed
    (-> (expect (:uf/db result))
        (.toBeTruthy))
    ;; Should have success response
    (-> (expect (some #(and (= :bg/fx.send-response (first %))
                            (-> % second :success)) (:uf/fxs result)))
        (.toBeTruthy))))

(defn- test-save-preserves-enabled-state-when-updating-existing-script []
  ;; Existing script is enabled=true
  (let [updated-script {:script/id "script-123"
                        :script/name "test.cljs"
                        :script/code "(updated code)"
                        :script/enabled false} ;; Trying to disable via update - should be ignored
        result (bg-actions/handle-action initial-state uf-data
                 [:fs/ax.save-script updated-script])
        saved-script (-> result :uf/db :storage/scripts first)]
    ;; Should preserve original enabled=true since we merge existing
    (-> (expect (:script/enabled saved-script))
        (.toBe true))))

(defn- test-save-defaults-new-scripts-to-disabled []
  (let [new-script {:script/id "script-new"
                    :script/name "brand-new.cljs"
                    :script/code "(new code)"}
        result (bg-actions/handle-action initial-state uf-data
                 [:fs/ax.save-script new-script])
        saved-script (->> result :uf/db :storage/scripts
                          (filter #(= (:script/id %) "script-new"))
                          first)]
    ;; New user scripts should default to disabled
    (-> (expect (:script/enabled saved-script))
        (.toBe false))))

(defn- test-save-allows-manual-only-script []
  (let [manual-script {:script/name "manual.cljs"
                       :script/code "(println \"manual script\")"
                       :script/match []}
        result (bg-actions/handle-action initial-state uf-data
                                         [:fs/ax.save-script manual-script])
        saved-script (->> result :uf/db :storage/scripts
                          (filter #(= (:script/name %) "manual.cljs"))
                          first)]
    ;; Should have state update
    (-> (expect (:uf/db result))
        (.toBeTruthy))
    ;; Script should be saved with empty match
    (-> (expect (:script/match saved-script))
        (.toEqual []))
    ;; Script should be disabled (no auto-run patterns)
    (-> (expect (:script/enabled saved-script))
        (.toBe false))
    ;; Should have persist and success
    (-> (expect (some #(= :storage/fx.persist! (first %)) (:uf/fxs result)))
        (.toBeTruthy))
    (-> (expect (some #(and (= :bg/fx.send-response (first %))
                            (-> % second :success)) (:uf/fxs result)))
        (.toBeTruthy))))

(describe ":fs/ax.save-script"
          (fn []
            (test "rejects when updating a builtin script" test-save-rejects-when-updating-a-builtin-script)
            (test "rejects when name exists and not force (create case)" test-save-rejects-when-name-exists-and-not-force)
            (test "force overwrite preserves existing script ID" test-save-force-overwrite-preserves-existing-script-id)
            (test "allows create when name is new" test-save-allows-create-when-name-is-new)
            (test "allows update when script exists by ID (non-builtin)" test-save-allows-update-when-script-exists-by-id)
            (test "allows overwrite when force flag set" test-save-allows-overwrite-when-force-flag-set)
            (test "preserves enabled state when updating existing script" test-save-preserves-enabled-state-when-updating-existing-script)
            (test "defaults new scripts to disabled" test-save-defaults-new-scripts-to-disabled)
            (test "allows manual-only script" test-save-allows-manual-only-script)))

;; ============================================================
;; Base Info Return Shape Tests
;; ============================================================

(defn- test-base-info-excludes-transport-envelope-keys []
  (let [result (repl-fs-actions/script->base-info base-script)]
    (-> (expect (contains? result :requestId))
        (.toBe false))
    (-> (expect (contains? result :source))
        (.toBe false))
    (-> (expect (contains? result :type))
        (.toBe false))
    (-> (expect (contains? result :fs/name))
        (.toBe true))
    (-> (expect (contains? result :fs/created))
        (.toBe true))
    (-> (expect (contains? result :fs/modified))
        (.toBe true))))

(describe "script->base-info"
          (fn []
            (test "excludes transport envelope keys" test-base-info-excludes-transport-envelope-keys)))

;; ============================================================
;; Save Script - Built-in Name Protection Tests
;; ============================================================

;; Real scenario: Builtin scripts have display names like "GitHub Gist Installer (Built-in)"
;; but the REPL normalizes names, so an attacker calling:
;;   (save! "GitHub Gist Installer (Built-in)" code)
;; sends normalized name "github_gist_installer_built_in.cljs" to the action handler.
;; The handler must detect this and reject it.

(def builtin-with-display-name
  (assoc base-script
         :script/id "script-builtin-2"
         :script/name "GitHub Gist Installer (Built-in)"
         :script/builtin? true))

(defn- test-builtin-protection-rejects-creating-script-with-normalized-builtin-name []
  ;; Attacker calls (save! "GitHub Gist Installer (Built-in)" code)
  ;; which normalizes to "github_gist_installer_built_in.cljs"
  ;; The builtin has :script/name "GitHub Gist Installer (Built-in)"
  ;; so find-script-by-name won't match - but we need protection!
  (let [state {:storage/scripts [builtin-with-display-name]}
        new-script {:script/id "script-attacker"
                    :script/name "github_gist_installer_built_in.cljs"
                    :script/code "(malicious code)"}
        result (bg-actions/handle-action state uf-data
                 [:fs/ax.save-script new-script])
        error-response (some #(when (= :bg/fx.send-response (first %)) (second %)) (:uf/fxs result))]
    (-> (expect error-response)
        (.toBeTruthy))
    (-> (expect (:success error-response))
        (.toBe false))
    (-> (expect (:error error-response))
        (.toContain "built-in"))))

(defn- test-builtin-protection-rejects-creating-script-even-with-force-flag []
  (let [state {:storage/scripts [builtin-with-display-name]}
        new-script {:script/id "script-attacker"
                    :script/name "github_gist_installer_built_in.cljs"
                    :script/code "(malicious code)"
                    :script/force? true}
        result (bg-actions/handle-action state uf-data
                 [:fs/ax.save-script new-script])
        error-response (some #(when (= :bg/fx.send-response (first %)) (second %)) (:uf/fxs result))]
    (-> (expect error-response)
        (.toBeTruthy))
    (-> (expect (:success error-response))
        (.toBe false))
    (-> (expect (:error error-response))
        (.toContain "built-in"))))

(describe ":fs/ax.save-script - built-in name protection"
          (fn []
            (test "rejects when creating script with normalized builtin name" test-builtin-protection-rejects-creating-script-with-normalized-builtin-name)
            (test "rejects when creating script with normalized builtin name even with force flag" test-builtin-protection-rejects-creating-script-even-with-force-flag)))

;; ============================================================
;; epupp/ Namespace Reservation Tests
;; ============================================================

(defn- test-epupp-namespace-rejects-uppercase-epupp-prefix []
  (let [state {:storage/scripts []}
        new-script {:script/id "script-attacker"
                    :script/name "EPUPP/my-script.cljs"
                    :script/code "(println \"test\")"}]
    (let [result (bg-actions/handle-action state uf-data
                   [:fs/ax.save-script new-script])
          error-response (some #(when (= :bg/fx.send-response (first %)) (second %)) (:uf/fxs result))]
      (-> (expect error-response)
          (.toBeTruthy))
      (-> (expect (:success error-response))
          (.toBe false))
      (-> (expect (:error error-response))
          (.toContain "reserved namespace")))))

(defn- test-epupp-namespace-rejects-when-creating-script-with-epupp-prefix []
  (let [state {:storage/scripts []}
        new-script {:script/id "script-attacker"
                    :script/name "epupp/my-script.cljs"
                    :script/code "(println \"test\")"}
        result (bg-actions/handle-action state uf-data
                 [:fs/ax.save-script new-script])
        error-response (some #(when (= :bg/fx.send-response (first %)) (second %)) (:uf/fxs result))]
    (-> (expect error-response)
        (.toBeTruthy))
    (-> (expect (:success error-response))
        (.toBe false))
    (-> (expect (:error error-response))
        (.toContain "reserved namespace"))))

(defn- test-epupp-namespace-rejects-epupp-prefix-even-with-force-flag []
  (let [state {:storage/scripts []}
        new-script {:script/id "script-attacker"
                    :script/name "epupp/test.cljs"
                    :script/code "(println \"test\")"
                    :script/force? true}
        result (bg-actions/handle-action state uf-data
                 [:fs/ax.save-script new-script])
        error-response (some #(when (= :bg/fx.send-response (first %)) (second %)) (:uf/fxs result))]
    (-> (expect error-response)
        (.toBeTruthy))
    (-> (expect (:success error-response))
        (.toBe false))
    (-> (expect (:error error-response))
        (.toContain "reserved namespace"))))

(defn- test-epupp-namespace-rejects-epupp-built-in-prefix []
  (let [state {:storage/scripts []}
        new-script {:script/id "script-attacker"
                    :script/name "epupp/built-in/fake.cljs"
                    :script/code "(println \"test\")"}
        result (bg-actions/handle-action state uf-data
                 [:fs/ax.save-script new-script])
        error-response (some #(when (= :bg/fx.send-response (first %)) (second %)) (:uf/fxs result))]
    (-> (expect error-response)
        (.toBeTruthy))
    (-> (expect (:success error-response))
        (.toBe false))
    (-> (expect (:error error-response))
        (.toContain "reserved namespace"))))

(defn- test-epupp-namespace-allows-scripts-with-epupp-elsewhere-in-name []
  (let [state {:storage/scripts []}
        new-script {:script/id "script-ok"
                    :script/name "my-epupp-helper.cljs"
                    :script/code "(println \"test\")"}
        result (bg-actions/handle-action state uf-data
                 [:fs/ax.save-script new-script])]
    ;; Should succeed
    (-> (expect (:uf/db result))
        (.toBeTruthy))
    ;; Should have success response
    (-> (expect (some #(and (= :bg/fx.send-response (first %))
                            (-> % second :success)) (:uf/fxs result)))
        (.toBeTruthy))))

(describe ":fs/ax.save-script - epupp/ namespace reservation"
          (fn []
            (test "rejects when creating script with epupp/ prefix" test-epupp-namespace-rejects-when-creating-script-with-epupp-prefix)
            (test "rejects uppercase EPUPP/ prefix (case-bypass)" test-epupp-namespace-rejects-uppercase-epupp-prefix)
            (test "rejects epupp/ prefix even with force flag" test-epupp-namespace-rejects-epupp-prefix-even-with-force-flag)
            (test "rejects epupp/built-in/ prefix (deep nesting)" test-epupp-namespace-rejects-epupp-built-in-prefix)
            (test "allows scripts with epupp elsewhere in name" test-epupp-namespace-allows-scripts-with-epupp-elsewhere-in-name)))

;; ============================================================
;; Icon State Tests
;; ============================================================

(defn- test-icon-set-state-sets-icon-state-and-triggers-toolbar-update []
  (let [state {:icon/states {1 :disconnected}}
        result (bg-actions/handle-action state uf-data
                 [:icon/ax.set-state 1 :connected])]
    (-> (expect (get-in result [:uf/db :icon/states 1]))
        (.toBe :connected))
    (-> (expect (some #(= [:icon/fx.update-toolbar! 1 :connected] %) (:uf/fxs result)))
        (.toBeTruthy))))

(describe ":icon/ax.set-state"
          (fn []
            (test "sets icon state and triggers toolbar update" test-icon-set-state-sets-icon-state-and-triggers-toolbar-update)))

(defn- test-icon-clear-removes-icon-state-and-updates-toolbar []
  (let [state {:icon/states {1 :connected 2 :disconnected}}
        result (bg-actions/handle-action state uf-data
                 [:icon/ax.clear 1])]
    (-> (expect (get-in result [:uf/db :icon/states 1]))
        (.toBeUndefined))
    (-> (expect (count (:uf/fxs result)))
        (.toBe 1))
    (-> (expect (first (:uf/fxs result)))
        (.toEqual [:icon/fx.update-toolbar! 1 :disconnected]))))

(describe ":icon/ax.clear"
          (fn []
            (test "removes icon state and updates toolbar" test-icon-clear-removes-icon-state-and-updates-toolbar)))

(defn- test-icon-prune-keeps-only-valid-tab-ids []
  (let [state {:icon/states {1 :connected 2 :disconnected}}
        result (bg-actions/handle-action state uf-data
                 [:icon/ax.prune #{2}])]
    (-> (expect (get-in result [:uf/db :icon/states 1]))
        (.toBeUndefined))
    (-> (expect (get-in result [:uf/db :icon/states 2]))
        (.toBe :disconnected))
    (-> (expect (count (:uf/fxs result)))
        (.toBe 0))))

(describe ":icon/ax.prune"
          (fn []
            (test "keeps only valid tab ids" test-icon-prune-keeps-only-valid-tab-ids)))

;; ============================================================
;; Connection History Tests
;; ============================================================

(defn- test-history-track-adds-tab-and-port-to-history []
  (let [state {:connected-tabs/history {1 {:port 12345}}}
        result (bg-actions/handle-action state uf-data
                 [:history/ax.track 2 23456])]
    (-> (expect (get-in result [:uf/db :connected-tabs/history 2 :port]))
        (.toBe 23456))
    (-> (expect (count (:uf/fxs result)))
        (.toBe 0))))

(describe ":history/ax.track"
          (fn []
            (test "adds tab and port to history" test-history-track-adds-tab-and-port-to-history)))

(defn- test-history-forget-removes-tab-from-history []
  (let [state {:connected-tabs/history {1 {:port 12345} 2 {:port 23456}}}
        result (bg-actions/handle-action state uf-data
                 [:history/ax.forget 1])]
    (-> (expect (get-in result [:uf/db :connected-tabs/history 1]))
        (.toBeUndefined))
    (-> (expect (get-in result [:uf/db :connected-tabs/history 2 :port]))
        (.toBe 23456))
    (-> (expect (count (:uf/fxs result)))
        (.toBe 0))))

(describe ":history/ax.forget"
          (fn []
            (test "removes tab from history" test-history-forget-removes-tab-from-history)))

;; ============================================================
;; WebSocket Connection Tests
;; ============================================================

(defn- test-ws-register-registers-connection-info-without-effects []
  (let [conn {:ws/socket :dummy-ws
              :ws/port 5555
              :ws/tab-title "Example"
              :ws/tab-favicon "favicon.png"
              :ws/tab-url "https://example.com"}
        result (bg-actions/handle-action {:ws/connections {}} uf-data
                 [:ws/ax.register 9 conn])]
    (-> (expect (get-in result [:uf/db :ws/connections 9 :ws/port]))
        (.toBe 5555))
    (-> (expect (count (:uf/fxs result)))
        (.toBe 0))))

(describe ":ws/ax.register"
          (fn []
            (test "registers connection info without effects" test-ws-register-registers-connection-info-without-effects)))

(defn- test-ws-unregister-removes-connection-and-broadcasts-change []
  (let [conn {:ws/socket :dummy-ws
              :ws/port 5555}
        result (bg-actions/handle-action {:ws/connections {9 conn}} uf-data
                 [:ws/ax.unregister 9])]
    (-> (expect (get-in result [:uf/db :ws/connections 9]))
        (.toBeUndefined))
    (-> (expect (some #(= :ws/fx.broadcast-connections-changed! (first %))
                      (:uf/fxs result)))
        (.toBeTruthy))))

(describe ":ws/ax.unregister"
          (fn []
            (test "removes connection and broadcasts change" test-ws-unregister-removes-connection-and-broadcasts-change)))

;; ============================================================
;; Initialization Tests
;; ============================================================

(defn- test-init-ensure-initialized-returns-await-effect-when-promise-exists []
  (let [existing-promise (js/Promise.resolve true)
        state {:init/promise existing-promise}
        result (bg-actions/handle-action state uf-data
                 [:init/ax.ensure-initialized])]
    (-> (expect (count (:uf/fxs result)))
        (.toBe 1))
    (-> (expect (first (first (:uf/fxs result))))
        (.toEqual :uf/await))))

(defn- test-init-ensure-initialized-creates-promise-and-initialization-effect-when-missing []
  (let [result (bg-actions/handle-action {} uf-data
                 [:init/ax.ensure-initialized])
        promise (get-in result [:uf/db :init/promise])
        fxs (:uf/fxs result)
        fx (first fxs)]
    (-> (expect promise)
        (.toBeTruthy))
    (-> (expect (count fxs))
        (.toBe 1))
    (-> (expect (first fx))
        (.toBe :uf/await))
    (-> (expect (second fx))
        (.toBe :init/fx.initialize))
    (-> (expect (fn? (nth fx 2)))
        (.toBe true))
    (-> (expect (fn? (nth fx 3)))
        (.toBe true))))

(describe ":init/ax.ensure-initialized"
          (fn []
            (test "returns await effect when promise already exists" test-init-ensure-initialized-returns-await-effect-when-promise-exists)
            (test "creates promise and initialization effect when missing" test-init-ensure-initialized-creates-promise-and-initialization-effect-when-missing)))

(defn- test-init-clear-promise-clears-init-promise []
  (let [promise (js/Promise.resolve true)
        result (bg-actions/handle-action {:init/promise promise} uf-data
                 [:init/ax.clear-promise])]
    (-> (expect (get-in result [:uf/db :init/promise]))
        (.toBe nil))))

(describe ":init/ax.clear-promise"
          (fn []
            (test "clears init promise" test-init-clear-promise-clears-init-promise)))

;; ============================================================
;; Save Script - Manifest-Derived Fields in Response Tests
;; ============================================================

(defn- test-save-manifest-derived-fields-save-response-includes-description-from-manifest []
  (let [state {:storage/scripts []}
        code-with-description "{:epupp/script-name \"with_description.cljs\"
 :epupp/description \"A helpful script\"}

(ns my-script)
(println \"hello\")"
        new-script {:script/id "script-with-desc"
                    :script/name "with_description.cljs"
                    :script/code code-with-description}
        result (bg-actions/handle-action state uf-data
                 [:fs/ax.save-script new-script])
        response (some #(when (= :bg/fx.send-response (first %)) (second %)) (:uf/fxs result))]
    ;; Should have success response
    (-> (expect (:success response))
        (.toBe true))
    ;; Save response should include the description from the manifest
    (-> (expect (:fs/description response))
        (.toBe "A helpful script"))))

(describe ":fs/ax.save-script - manifest-derived fields in response"
          (fn []
            (test "save response includes :fs/description from manifest" test-save-manifest-derived-fields-save-response-includes-description-from-manifest)))

;; ============================================================
;; Base Info Return Shape Expanded Tests
;; ============================================================

(defn- test-base-info-required-fields-present []
  (let [script {:script/id "script-1"
                :script/name "test.cljs"
                :script/created "2026-01-15T10:00:00.000Z"
                :script/modified "2026-01-15T12:00:00.000Z"
                :script/code "(println \"test\")"}
        result (repl-fs-actions/script->base-info script)]
    (-> (expect (:fs/name result))
        (.toBe "test.cljs"))
    (-> (expect (:fs/created result))
        (.toBe "2026-01-15T10:00:00.000Z"))
    (-> (expect (:fs/modified result))
        (.toBe "2026-01-15T12:00:00.000Z"))))

(defn- test-base-info-optional-fields-omitted-when-nil []
  (let [script {:script/id "script-1"
                :script/name "minimal.cljs"
                :script/created "2026-01-15T10:00:00.000Z"
                :script/modified "2026-01-15T12:00:00.000Z"
                :script/code "(println \"test\")"
                :script/description nil
                :script/run-at nil
                :script/inject nil
                :script/match nil}
        result (repl-fs-actions/script->base-info script)]
    (-> (expect (contains? result :fs/description))
        (.toBe false))
    (-> (expect (contains? result :fs/run-at))
        (.toBe false))
    (-> (expect (contains? result :fs/inject))
        (.toBe false))
    (-> (expect (contains? result :fs/auto-run-match))
        (.toBe false))
    (-> (expect (contains? result :fs/enabled?))
        (.toBe false))))

(defn- test-base-info-match-patterns-as-vector []
  (let [script {:script/id "script-1"
                :script/name "test.cljs"
                :script/created "2026-01-15T10:00:00.000Z"
                :script/modified "2026-01-15T12:00:00.000Z"
                :script/code "(println \"test\")"
                :script/match ["https://github.com/*" "https://gitlab.com/*"]
                :script/enabled true}
        result (repl-fs-actions/script->base-info script)]
    (-> (expect (:fs/auto-run-match result))
        (.toEqual ["https://github.com/*" "https://gitlab.com/*"]))))

(defn- test-base-info-auto-run-and-enabled-when-script-has-patterns []
  (let [script {:script/id "script-1"
                :script/name "test.cljs"
                :script/created "2026-01-15T10:00:00.000Z"
                :script/modified "2026-01-15T12:00:00.000Z"
                :script/code "(println \"test\")"
                :script/match ["https://example.com/*"]
                :script/enabled true}
        result (repl-fs-actions/script->base-info script)]
    (-> (expect (contains? result :fs/auto-run-match))
        (.toBe true))
    (-> (expect (contains? result :fs/enabled?))
        (.toBe true))
    (-> (expect (:fs/enabled? result))
        (.toBe true))))

(defn- test-base-info-auto-run-and-enabled-omitted-when-no-patterns []
  (let [script {:script/id "script-1"
                :script/name "manual.cljs"
                :script/created "2026-01-15T10:00:00.000Z"
                :script/modified "2026-01-15T12:00:00.000Z"
                :script/code "(println \"test\")"
                :script/match []
                :script/enabled false}
        result (repl-fs-actions/script->base-info script)]
    (-> (expect (contains? result :fs/auto-run-match))
        (.toBe false))
    (-> (expect (contains? result :fs/enabled?))
        (.toBe false))))

(defn- test-base-info-description-when-present []
  (let [script {:script/id "script-1"
                :script/name "test.cljs"
                :script/created "2026-01-15T10:00:00.000Z"
                :script/modified "2026-01-15T12:00:00.000Z"
                :script/code "(println \"test\")"
                :script/description "A helpful script"}
        result (repl-fs-actions/script->base-info script)]
    (-> (expect (:fs/description result))
        (.toBe "A helpful script"))))

(defn- test-base-info-run-at-when-present []
  (let [script {:script/id "script-1"
                :script/name "test.cljs"
                :script/created "2026-01-15T10:00:00.000Z"
                :script/modified "2026-01-15T12:00:00.000Z"
                :script/code "(println \"test\")"
                :script/run-at "document-start"}
        result (repl-fs-actions/script->base-info script)]
    (-> (expect (:fs/run-at result))
        (.toBe "document-start"))))

(defn- test-base-info-inject-when-present []
  (let [script {:script/id "script-1"
                :script/name "test.cljs"
                :script/created "2026-01-15T10:00:00.000Z"
                :script/modified "2026-01-15T12:00:00.000Z"
                :script/code "(println \"test\")"
                :script/inject ["scittle://reagent.js" "scittle://re-frame.js"]}
        result (repl-fs-actions/script->base-info script)]
    (-> (expect (:fs/inject result))
        (.toEqual ["scittle://reagent.js" "scittle://re-frame.js"]))))

(defn- test-base-info-empty-description-omitted []
  (let [script {:script/id "script-1"
                :script/name "test.cljs"
                :script/created "2026-01-15T10:00:00.000Z"
                :script/modified "2026-01-15T12:00:00.000Z"
                :script/code "(println \"test\")"
                :script/description ""}
        result (repl-fs-actions/script->base-info script)]
    (-> (expect (contains? result :fs/description))
        (.toBe false))))

(defn- test-base-info-empty-inject-omitted []
  (let [script {:script/id "script-1"
                :script/name "test.cljs"
                :script/created "2026-01-15T10:00:00.000Z"
                :script/modified "2026-01-15T12:00:00.000Z"
                :script/code "(println \"test\")"
                :script/inject []}
        result (repl-fs-actions/script->base-info script)]
    (-> (expect (contains? result :fs/inject))
        (.toBe false))))

(describe "script->base-info - response shape validation"
          (fn []
            (test "required fields present" test-base-info-required-fields-present)
            (test "optional fields omitted when nil" test-base-info-optional-fields-omitted-when-nil)
            (test "match patterns as vector" test-base-info-match-patterns-as-vector)
            (test "auto-run and enabled when script has patterns" test-base-info-auto-run-and-enabled-when-script-has-patterns)
            (test "auto-run and enabled omitted when no patterns" test-base-info-auto-run-and-enabled-omitted-when-no-patterns)
            (test "description when present" test-base-info-description-when-present)
            (test "run-at when present" test-base-info-run-at-when-present)
            (test "inject when present" test-base-info-inject-when-present)
            (test "empty description omitted" test-base-info-empty-description-omitted)
            (test "empty inject omitted" test-base-info-empty-inject-omitted)))

;; ============================================================
;; Navigation Decision Action Tests
;; ============================================================

;; The :nav/ax.decide-connection action receives gathered context from
;; :nav/fx.gather-auto-connect-context and returns appropriate effects based on
;; the pure decide-auto-connection function.

(defn- test-nav-decide-connection-returns-connect-effect-for-connect-all []
  (let [context {:nav/tab-id 123
                 :nav/url "https://example.com"
                 :nav/auto-connect-enabled? true
                 :nav/auto-reconnect-enabled? false
                 :nav/in-history? false
                 :nav/history-port nil
                 :nav/saved-port "1340"}
        result (bg-actions/handle-action {} uf-data
                 [:nav/ax.decide-connection context])
        fxs (:uf/fxs result)]
    ;; Should return connect effect with saved-port
    (-> (expect (some #(and (= :uf/await (first %))
                            (= :nav/fx.connect (second %))
                            (= 123 (nth % 2))
                            (= "1340" (nth % 3))) fxs))
        (.toBeTruthy))))

(defn- test-nav-decide-connection-returns-connect-effect-for-reconnect []
  (let [context {:nav/tab-id 456
                 :nav/url "https://github.com"
                 :nav/auto-connect-enabled? false
                 :nav/auto-reconnect-enabled? true
                 :nav/in-history? true
                 :nav/history-port "1341"
                 :nav/saved-port "1340"}
        result (bg-actions/handle-action {} uf-data
                 [:nav/ax.decide-connection context])
        fxs (:uf/fxs result)]
    ;; Should return connect effect with history-port
    (-> (expect (some #(and (= :uf/await (first %))
                            (= :nav/fx.connect (second %))
                            (= 456 (nth % 2))
                            (= "1341" (nth % 3))) fxs))
        (.toBeTruthy))))

(defn- test-nav-decide-connection-returns-no-connect-effect-when-none []
  (let [context {:nav/tab-id 789
                 :nav/url "https://example.com"
                 :nav/auto-connect-enabled? false
                 :nav/auto-reconnect-enabled? false
                 :nav/in-history? false
                 :nav/history-port nil
                 :nav/saved-port "1340"}
        result (bg-actions/handle-action {} uf-data
                 [:nav/ax.decide-connection context])
        fxs (:uf/fxs result)]
    ;; Should not return any connect effect
    (-> (expect (some #(= :nav/fx.connect (if (= :uf/await (first %)) (second %) (first %))) fxs))
        (.toBeFalsy))))

(defn- test-nav-decide-connection-always-returns-process-navigation-effect []
  ;; Navigation processing (userscripts) should always happen regardless of connection decision
  (let [context {:nav/tab-id 123
                 :nav/url "https://example.com"
                 :nav/auto-connect-enabled? false
                 :nav/auto-reconnect-enabled? false
                 :nav/in-history? false
                 :nav/history-port nil
                 :nav/saved-port "1340"}
        result (bg-actions/handle-action {} uf-data
                 [:nav/ax.decide-connection context])
        fxs (:uf/fxs result)]
    ;; Should return process-navigation effect
    (-> (expect (some #(and (= :nav/fx.process-navigation (if (= :uf/await (first %)) (second %) (first %)))
                            (= 123 (nth % (if (= :uf/await (first %)) 2 1)))
                            (= "https://example.com" (nth % (if (= :uf/await (first %)) 3 2)))) fxs))
        (.toBeTruthy))))

(describe ":nav/ax.decide-connection"
          (fn []
            (test "returns connect effect with saved-port for connect-all decision" test-nav-decide-connection-returns-connect-effect-for-connect-all)
            (test "returns connect effect with history-port for reconnect decision" test-nav-decide-connection-returns-connect-effect-for-reconnect)
            (test "returns no connect effect when decision is none" test-nav-decide-connection-returns-no-connect-effect-when-none)
            (test "always returns process-navigation effect" test-nav-decide-connection-always-returns-process-navigation-effect)))
