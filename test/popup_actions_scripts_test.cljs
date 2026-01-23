(ns popup-actions-scripts-test
  "Tests for popup script-related action handlers - pure state transitions"
  (:require ["vitest" :refer [describe test expect]]
            [popup-actions :as popup-actions]))

;; ============================================================
;; Shared Setup
;; ============================================================

(def initial-state
  {:ports/nrepl "1339"
   :ports/ws "1340"
   :ui/status nil
   :ui/copy-feedback nil
   :ui/has-connected false
   :ui/sections-collapsed {:repl-connect false
                           :matching-scripts false
                           :other-scripts false
                           :settings true}
   :ui/leaving-scripts #{}
   :ui/leaving-origins #{}
   :ui/leaving-tabs #{}
   :browser/brave? false
   :scripts/list []
   :scripts/current-url nil
   :settings/user-origins []
   :settings/new-origin ""
   :settings/default-origins []})

(def uf-data {:system/now 1234567890
              :config/deps-string "{:deps {}}"})

;; ============================================================
;; Test Functions
;; ============================================================

;; Script actions

(defn- test-toggle-script-passes-data-to-effect []
  (let [scripts [{:script/id "test-1" :script/enabled true}]
        state (assoc initial-state :scripts/list scripts)
        result (popup-actions/handle-action state uf-data [:popup/ax.toggle-script "test-1" "*://example.com/*"])
        [fx-name fx-scripts fx-id fx-pattern] (first (:uf/fxs result))]
    (-> (expect fx-name)
        (.toBe :popup/fx.toggle-script))
    (-> (expect (count fx-scripts))
        (.toBe 1))
    (-> (expect fx-id)
        (.toBe "test-1"))
    (-> (expect fx-pattern)
        (.toBe "*://example.com/*"))))

(defn- test-delete-script-removes-and-triggers-effect []
  (let [scripts [{:script/id "test-1"} {:script/id "test-2"}]
        state (assoc initial-state :scripts/list scripts)
        result (popup-actions/handle-action state uf-data [:popup/ax.delete-script "test-1"])
        [fx-name _fx-scripts fx-id] (first (:uf/fxs result))]
    ;; Should immediately remove from list
    (-> (expect (count (:scripts/list (:uf/db result))))
        (.toBe 1))
    (-> (expect (:script/id (first (:scripts/list (:uf/db result)))))
        (.toBe "test-2"))
    ;; Should trigger storage effect
    (-> (expect fx-name)
        (.toBe :popup/fx.delete-script))
    (-> (expect fx-id)
        (.toBe "test-1"))))

;; Inspect action

(defn- test-inspect-script-triggers-effect-and-schedules-banner []
  (let [scripts [{:script/id "test-1"
                  :script/name "Test"
                  :script/match ["*://example.com/*"]
                  :script/code "(println \"hi\")"}]
        state (assoc initial-state :scripts/list scripts)
        result (popup-actions/handle-action state uf-data [:popup/ax.inspect-script "test-1"])]
    ;; Should trigger inspect effect
    (-> (expect (first (first (:uf/fxs result))))
        (.toBe :popup/fx.inspect-script))
    ;; Second effect should be defer-dispatch for system-banner
    (let [[fx-name [[action event-type _message]]] (second (:uf/fxs result))]
      (-> (expect fx-name)
          (.toBe :uf/fx.defer-dispatch))
      (-> (expect action)
          (.toBe :popup/ax.show-system-banner))
      (-> (expect event-type)
          (.toBe "info")))))

(defn- test-inspect-script-passes-script-to-effect []
  (let [scripts [{:script/id "test-1"
                  :script/name "Test Script"
                  :script/match ["*://example.com/*"]
                  :script/code "(println \"hello\")"}]
        state (assoc initial-state :scripts/list scripts)
        result (popup-actions/handle-action state uf-data [:popup/ax.inspect-script "test-1"])
        [_fx-name script] (first (:uf/fxs result))]
    (-> (expect (:script/id script))
        (.toBe "test-1"))
    (-> (expect (:script/name script))
        (.toBe "Test Script"))))

(defn- test-inspect-script-returns-nil-for-non-existent []
  (let [scripts [{:script/id "other"}]
        state (assoc initial-state :scripts/list scripts)
        result (popup-actions/handle-action state uf-data [:popup/ax.inspect-script "missing"])]
    (-> (expect result)
        (.toBeUndefined))))

;; Evaluate action

(defn- test-evaluate-script-triggers-effect []
  (let [scripts [{:script/id "test-1"
                  :script/name "Test"
                  :script/match ["*://example.com/*"]
                  :script/code "(println \"hi\")"}]
        state (assoc initial-state :scripts/list scripts)
        result (popup-actions/handle-action state uf-data [:popup/ax.evaluate-script "test-1"])
        [fx-name script] (first (:uf/fxs result))]
    (-> (expect fx-name)
        (.toBe :popup/fx.evaluate-script))
    (-> (expect (:script/id script))
        (.toBe "test-1"))
    (-> (expect (:script/code script))
        (.toBe "(println \"hi\")"))))

(defn- test-evaluate-script-returns-nil-for-non-existent []
  (let [scripts [{:script/id "other"}]
        state (assoc initial-state :scripts/list scripts)
        result (popup-actions/handle-action state uf-data [:popup/ax.evaluate-script "missing"])]
    (-> (expect result)
        (.toBeUndefined))))

;; ============================================================
;; Test Registration
;; ============================================================

(describe "popup script actions"
          (fn []
            ;; Script actions
            (test ":popup/ax.toggle-script passes data to effect" test-toggle-script-passes-data-to-effect)
            (test ":popup/ax.delete-script removes and triggers effect" test-delete-script-removes-and-triggers-effect)

            ;; Inspect action
            (test ":popup/ax.inspect-script triggers effect and schedules banner" test-inspect-script-triggers-effect-and-schedules-banner)
            (test ":popup/ax.inspect-script passes script to effect" test-inspect-script-passes-script-to-effect)
            (test ":popup/ax.inspect-script returns nil for non-existent" test-inspect-script-returns-nil-for-non-existent)

            ;; Evaluate action
            (test ":popup/ax.evaluate-script triggers effect" test-evaluate-script-triggers-effect)
            (test ":popup/ax.evaluate-script returns nil for non-existent" test-evaluate-script-returns-nil-for-non-existent)))
