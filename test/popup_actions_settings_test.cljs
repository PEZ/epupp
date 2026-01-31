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
;; User Origins Tests
;; ============================================================

(defn- ^:async test-load-user-origins-triggers-effect []
  (let [result (popup-actions/handle-action initial-state uf-data [:popup/ax.load-user-origins])]
    (-> (expect (first (first (:uf/fxs result))))
        (.toBe :popup/fx.load-user-origins))))

(defn- ^:async test-set-new-origin-updates-state []
  (let [result (popup-actions/handle-action initial-state uf-data [:popup/ax.set-new-origin "https://example.com/"])
        new-origin (:settings/new-origin (:uf/db result))]
    (-> (expect new-origin)
        (.toBe "https://example.com/"))))

(defn- ^:async test-add-origin-adds-valid-origin-and-triggers-effect []
  (let [state (-> initial-state
                  (assoc :settings/new-origin "https://example.com/*")
                  (assoc :settings/user-origins [])
                  (assoc :settings/default-origins []))
        result (popup-actions/handle-action state uf-data [:popup/ax.add-origin])
        [fx-name origin] (first (:uf/fxs result))]
    ;; Should immediately add to list
    (-> (expect (first (:settings/user-origins (:uf/db result))))
        (.toBe "https://example.com/*"))
    ;; Should clear input
    (-> (expect (:settings/new-origin (:uf/db result)))
        (.toBe ""))
    ;; Should trigger storage effect
    (-> (expect fx-name)
        (.toBe :popup/fx.add-user-origin))
    (-> (expect origin)
        (.toBe "https://example.com/*"))))

(defn- ^:async test-add-origin-rejects-invalid-no-trailing-slash []
  (let [state (-> initial-state
                  (assoc :settings/new-origin "https://example.com")
                  (assoc :settings/user-origins [])
                  (assoc :settings/default-origins []))
        result (popup-actions/handle-action state uf-data [:popup/ax.add-origin])
        [action event-type message _] (first (:uf/dxs result))]
    ;; Should dispatch error banner via :uf/dxs
    (-> (expect action)
        (.toBe :popup/ax.show-system-banner))
    (-> (expect event-type)
        (.toBe "error"))
    (-> (expect message)
        (.toBe "Must start with http:// or https:// and contain * (glob pattern) or be a complete URL"))))

(defn- ^:async test-add-origin-rejects-invalid-wrong-protocol []
  (let [state (-> initial-state
                  (assoc :settings/new-origin "ftp://example.com/")
                  (assoc :settings/user-origins [])
                  (assoc :settings/default-origins []))
        result (popup-actions/handle-action state uf-data [:popup/ax.add-origin])
        [action event-type _message _] (first (:uf/dxs result))]
    ;; Should dispatch error banner via :uf/dxs
    (-> (expect action)
        (.toBe :popup/ax.show-system-banner))
    (-> (expect event-type)
        (.toBe "error"))))

(defn- ^:async test-add-origin-rejects-duplicate-in-user-list []
  (let [state (-> initial-state
                  (assoc :settings/new-origin "https://example.com/*")
                  (assoc :settings/user-origins ["https://example.com/*"])
                  (assoc :settings/default-origins []))
        result (popup-actions/handle-action state uf-data [:popup/ax.add-origin])
        [action event-type message _] (first (:uf/dxs result))]
    ;; Should dispatch error banner via :uf/dxs
    (-> (expect action)
        (.toBe :popup/ax.show-system-banner))
    (-> (expect event-type)
        (.toBe "error"))
    (-> (expect message)
        (.toBe "Origin already exists"))))

(defn- ^:async test-add-origin-rejects-duplicate-in-default-list []
  (let [state (-> initial-state
                  (assoc :settings/new-origin "https://github.com/*")
                  (assoc :settings/user-origins [])
                  (assoc :settings/default-origins ["https://github.com/*"]))
        result (popup-actions/handle-action state uf-data [:popup/ax.add-origin])
        ;; Should dispatch error banner via :uf/dxs
        [action event-type message _] (first (:uf/dxs result))]
    (-> (expect action)
        (.toBe :popup/ax.show-system-banner))
    (-> (expect event-type)
        (.toBe "error"))
    (-> (expect message)
        (.toBe "Origin already exists"))))

(defn- ^:async test-remove-origin-removes-and-triggers-effect []
  (let [state (assoc initial-state :settings/user-origins ["https://a.com/" "https://b.com/"])
        result (popup-actions/handle-action state uf-data [:popup/ax.remove-origin "https://a.com/"])
        [fx-name origin] (first (:uf/fxs result))]
    ;; Should immediately remove from list
    (-> (expect (count (:settings/user-origins (:uf/db result))))
        (.toBe 1))
    (-> (expect (first (:settings/user-origins (:uf/db result))))
        (.toBe "https://b.com/"))
    ;; Should trigger storage effect
    (-> (expect fx-name)
        (.toBe :popup/fx.remove-user-origin))
    (-> (expect origin)
        (.toBe "https://a.com/"))))

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
    ;; User origins
    (test "load-user-origins triggers effect" test-load-user-origins-triggers-effect)
    (test "set-new-origin updates state" test-set-new-origin-updates-state)
    (test "add-origin adds valid origin and triggers effect" test-add-origin-adds-valid-origin-and-triggers-effect)
    (test "add-origin rejects invalid: no trailing slash" test-add-origin-rejects-invalid-no-trailing-slash)
    (test "add-origin rejects invalid: wrong protocol" test-add-origin-rejects-invalid-wrong-protocol)
    (test "add-origin rejects duplicate in user list" test-add-origin-rejects-duplicate-in-user-list)
    (test "add-origin rejects duplicate in default list" test-add-origin-rejects-duplicate-in-default-list)
    (test "remove-origin removes and triggers effect" test-remove-origin-removes-and-triggers-effect)

    ;; Auto-reconnect
    (test "load-auto-reconnect-setting triggers effect" test-load-auto-reconnect-setting-triggers-effect)
    (test "toggle-auto-reconnect-repl toggles true to false" test-toggle-auto-reconnect-repl-toggles-true-to-false)
    (test "toggle-auto-reconnect-repl toggles false to true" test-toggle-auto-reconnect-repl-toggles-false-to-true)))
