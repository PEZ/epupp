(ns popup-actions-connection-test
  "Tests for popup connection-related action handlers"
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

;; Port actions

(defn- test-set-nrepl-port-updates-and-saves []
  (let [result (popup-actions/handle-action initial-state uf-data [:popup/ax.set-nrepl-port "12345"])]
    (-> (expect (:ports/nrepl (:uf/db result)))
        (.toBe "12345"))
    ;; Should trigger save effect
    (-> (expect (first (first (:uf/fxs result))))
        (.toBe :popup/fx.save-ports))))

(defn- test-set-ws-port-updates-and-saves []
  (let [result (popup-actions/handle-action initial-state uf-data [:popup/ax.set-ws-port "12346"])]
    (-> (expect (:ports/ws (:uf/db result)))
        (.toBe "12346"))
    ;; Should trigger save effect
    (-> (expect (first (first (:uf/fxs result))))
        (.toBe :popup/fx.save-ports))))

(defn- test-set-nrepl-port-preserves-other-port []
  (let [result (popup-actions/handle-action initial-state uf-data [:popup/ax.set-nrepl-port "9999"])
        [_fx-name ports] (first (:uf/fxs result))]
    (-> (expect (:ports/nrepl ports))
        (.toBe "9999"))
    (-> (expect (:ports/ws ports))
        (.toBe "1340"))))

;; Copy command

(defn- test-copy-command-generates-with-ports []
  (let [result (popup-actions/handle-action initial-state uf-data [:popup/ax.copy-command])
        [fx-name cmd] (first (:uf/fxs result))]
    (-> (expect fx-name)
        (.toBe :popup/fx.copy-command))
    ;; Command should contain the ports
    (-> (expect (.includes cmd "1339"))
        (.toBe true))
    (-> (expect (.includes cmd "1340"))
        (.toBe true))))

(defn- test-copy-command-uses-deps-string []
  (let [custom-uf-data {:config/deps-string "{:deps {foo/bar {:mvn/version \"1.0\"}}}"}
        result (popup-actions/handle-action initial-state custom-uf-data [:popup/ax.copy-command])
        [_fx-name cmd] (first (:uf/fxs result))]
    (-> (expect (.includes cmd "foo/bar"))
        (.toBe true))))

;; Connect

(defn- test-connect-triggers-effect []
  (let [result (popup-actions/handle-action initial-state uf-data [:popup/ax.connect])]
    ;; Action only triggers effect - status is set by the effect itself
    (-> (expect (first (first (:uf/fxs result))))
        (.toBe :popup/fx.connect))))

(defn- test-connect-passes-parsed-port []
  (let [result (popup-actions/handle-action initial-state uf-data [:popup/ax.connect])
        [_fx-name port] (first (:uf/fxs result))]
    (-> (expect port)
        (.toBe 1340))))

(defn- test-connect-returns-nil-for-invalid-port []
  (let [state (assoc initial-state :ports/ws "invalid")
        result (popup-actions/handle-action state uf-data [:popup/ax.connect])]
    (-> (expect result)
        (.toBeUndefined))))

(defn- test-connect-returns-nil-for-out-of-range-port []
  (let [state (assoc initial-state :ports/ws "70000")
        result (popup-actions/handle-action state uf-data [:popup/ax.connect])]
    (-> (expect result)
        (.toBeUndefined))))

;; Load actions

(defn- test-check-status-triggers-effect []
  (let [result (popup-actions/handle-action initial-state uf-data [:popup/ax.check-status])
        [fx-name ws-port] (first (:uf/fxs result))]
    (-> (expect fx-name)
        (.toBe :popup/fx.check-status))
    (-> (expect ws-port)
        (.toBe "1340"))))

(defn- test-load-saved-ports-triggers-effect []
  (let [result (popup-actions/handle-action initial-state uf-data [:popup/ax.load-saved-ports])]
    (-> (expect (first (first (:uf/fxs result))))
        (.toBe :popup/fx.load-saved-ports))))

(defn- test-load-scripts-triggers-effect []
  (let [result (popup-actions/handle-action initial-state uf-data [:popup/ax.load-scripts])]
    (-> (expect (first (first (:uf/fxs result))))
        (.toBe :popup/fx.load-scripts))))

(defn- test-load-current-url-triggers-effect []
  (let [result (popup-actions/handle-action initial-state uf-data [:popup/ax.load-current-url])]
    (-> (expect (first (first (:uf/fxs result))))
        (.toBe :popup/fx.load-current-url))))

;; ============================================================
;; Test Registration
;; ============================================================

(describe "popup connection actions"
          (fn []
            ;; Port actions
            (test ":popup/ax.set-nrepl-port updates and saves" test-set-nrepl-port-updates-and-saves)
            (test ":popup/ax.set-ws-port updates and saves" test-set-ws-port-updates-and-saves)
            (test ":popup/ax.set-nrepl-port preserves other port" test-set-nrepl-port-preserves-other-port)

            ;; Copy command
            (test ":popup/ax.copy-command generates with ports" test-copy-command-generates-with-ports)
            (test ":popup/ax.copy-command uses deps string" test-copy-command-uses-deps-string)

            ;; Connect
            (test ":popup/ax.connect triggers effect" test-connect-triggers-effect)
            (test ":popup/ax.connect passes parsed port" test-connect-passes-parsed-port)
            (test ":popup/ax.connect returns nil for invalid port" test-connect-returns-nil-for-invalid-port)
            (test ":popup/ax.connect returns nil for out of range port" test-connect-returns-nil-for-out-of-range-port)

            ;; Load actions
            (test ":popup/ax.check-status triggers effect" test-check-status-triggers-effect)
            (test ":popup/ax.load-saved-ports triggers effect" test-load-saved-ports-triggers-effect)
            (test ":popup/ax.load-scripts triggers effect" test-load-scripts-triggers-effect)
            (test ":popup/ax.load-current-url triggers effect" test-load-current-url-triggers-effect)))
