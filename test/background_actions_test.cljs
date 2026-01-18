(ns background-actions-test
  "Tests for background FS action handlers - pure decision logic"
  (:require ["vitest" :refer [describe test expect]]
            [background-actions :as bg-actions]))

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
   :script/approved-patterns []
   :script/run-at "document-idle"
   :script/require []})

(def builtin-script
  (assoc base-script
         :script/id "epupp-builtin-gist-installer"
         :script/name "gist-installer.cljs"))

(def initial-state
  {:storage/scripts [base-script]
   :storage/granted-origins []
   :storage/user-allowed-origins []})

(def uf-data {:system/now 1737100000000})

;; ============================================================
;; Rename Script Tests
;; ============================================================

(describe ":fs/ax.rename-script"
  (fn []
    (test "rejects when source script not found"
      (fn []
        (let [result (bg-actions/handle-action initial-state uf-data
                       [:fs/ax.rename-script "nonexistent.cljs" "new-name.cljs"])
              error-response (some #(when (= :bg/fx.send-response (first %)) (second %)) (:uf/fxs result))]
          (-> (expect error-response)
              (.toBeTruthy))
          (-> (expect (:success error-response))
              (.toBe false))
          (-> (expect (:error error-response))
              (.toContain "Script not found")))))

    (test "rejects when source is builtin script"
      (fn []
        (let [state {:storage/scripts [builtin-script]}
              result (bg-actions/handle-action state uf-data
                       [:fs/ax.rename-script "gist-installer.cljs" "renamed.cljs"])
              error-response (some #(when (= :bg/fx.send-response (first %)) (second %)) (:uf/fxs result))]
          (-> (expect error-response)
              (.toBeTruthy))
          (-> (expect (:success error-response))
              (.toBe false))
          (-> (expect (:error error-response))
              (.toContain "built-in")))))

    (test "rejects when target name already exists"
      (fn []
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
              (.toContain "already exists")))))

    (test "allows rename when target name is free"
      (fn []
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
              (.toBeTruthy)))))

    (test "updates modified timestamp on rename"
      (fn []
        (let [result (bg-actions/handle-action initial-state uf-data
                       [:fs/ax.rename-script "test.cljs" "renamed.cljs"])
              modified (-> result :uf/db :storage/scripts first :script/modified)]
          ;; Modified should be updated (not the original value)
          (-> (expect modified)
              (.not.toBe "2026-01-01T00:00:00.000Z")))))))

;; ============================================================
;; Delete Script Tests
;; ============================================================

(describe ":fs/ax.delete-script"
  (fn []
    (test "rejects when script not found"
      (fn []
        (let [result (bg-actions/handle-action initial-state uf-data
                       [:fs/ax.delete-script "nonexistent.cljs"])
              error-response (some #(when (= :bg/fx.send-response (first %)) (second %)) (:uf/fxs result))]
          (-> (expect error-response)
              (.toBeTruthy))
          (-> (expect (:success error-response))
              (.toBe false))
          (-> (expect (:error error-response))
              (.toContain "Not deleting non-existent file")))))

    (test "rejects when script is builtin"
      (fn []
        (let [state {:storage/scripts [builtin-script]}
              result (bg-actions/handle-action state uf-data
                       [:fs/ax.delete-script "gist-installer.cljs"])
              error-response (some #(when (= :bg/fx.send-response (first %)) (second %)) (:uf/fxs result))]
          (-> (expect error-response)
              (.toBeTruthy))
          (-> (expect (:success error-response))
              (.toBe false))
          (-> (expect (:error error-response))
              (.toContain "built-in")))))

    (test "allows delete and removes from state"
      (fn []
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
              (.toBeTruthy)))))))

;; ============================================================
;; Save Script Tests
;; ============================================================

(describe ":fs/ax.save-script"
  (fn []
    (test "rejects when updating a builtin script"
      (fn []
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
              (.toContain "built-in")))))

    (test "rejects when name exists and not force (create case)"
      (fn []
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
              (.toContain "already exists")))))

    (test "allows create when name is new"
      (fn []
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
              (.toBeTruthy)))))

    (test "allows update when script exists by ID (non-builtin)"
      (fn []
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
              (.toBeTruthy)))))

    (test "allows overwrite when force flag set"
      (fn []
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
              (.toBeTruthy)))))))

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
         :script/id "epupp-builtin-gist-installer"
         :script/name "GitHub Gist Installer (Built-in)"))

(describe ":fs/ax.save-script - built-in name protection"
  (fn []
    (test "rejects when creating script with normalized builtin name"
      (fn []
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
              (.toContain "built-in")))))

    (test "rejects when creating script with normalized builtin name even with force flag"
      (fn []
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
              (.toContain "built-in")))))))
