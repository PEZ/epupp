(ns popup-actions-settings-test
  "Tests for popup settings-related action handlers"
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
                           :settings true}})

(def uf-data {:system/now 1234567890
              :config/deps-string "{:deps {}}"})

;; ============================================================
;; Auto-Reconnect Tests
;; ============================================================

(defn- ^:async test-load-auto-reconnect-setting-triggers-effect []
  (let [result (popup-actions/handle-action initial-state uf-data [:popup/ax.load-auto-reconnect-setting])]
    (-> (expect (first (first (:uf/fxs result))))
        (.toBe :popup/fx.load-auto-reconnect-setting))))

(defn- ^:async test-toggle-auto-reconnect-repl-toggles-true-to-false []
  (let [state (assoc initial-state :settings/auto-reconnect-repl true)
        result (popup-actions/handle-action state uf-data [:popup/ax.toggle-auto-reconnect-repl])]
    (-> (expect (:settings/auto-reconnect-repl (:uf/db result)))
        (.toBe false))
    ;; Should trigger save effect
    (let [[fx-name enabled] (first (:uf/fxs result))]
      (-> (expect fx-name)
          (.toBe :popup/fx.save-auto-reconnect-setting))
      (-> (expect enabled)
          (.toBe false)))))

(defn- ^:async test-toggle-auto-reconnect-repl-toggles-false-to-true []
  (let [state (assoc initial-state :settings/auto-reconnect-repl false)
        result (popup-actions/handle-action state uf-data [:popup/ax.toggle-auto-reconnect-repl])]
    (-> (expect (:settings/auto-reconnect-repl (:uf/db result)))
        (.toBe true))
    ;; Should trigger save effect
    (let [[fx-name enabled] (first (:uf/fxs result))]
      (-> (expect fx-name)
          (.toBe :popup/fx.save-auto-reconnect-setting))
      (-> (expect enabled)
          (.toBe true)))))

;; ============================================================
;; Test Registration
;; ============================================================

(describe "Popup Settings Actions"
  (fn []
    ;; Auto-reconnect
    (test "load-auto-reconnect-setting triggers effect" test-load-auto-reconnect-setting-triggers-effect)
    (test "toggle-auto-reconnect-repl toggles true to false" test-toggle-auto-reconnect-repl-toggles-true-to-false)
    (test "toggle-auto-reconnect-repl toggles false to true" test-toggle-auto-reconnect-repl-toggles-false-to-true)))
