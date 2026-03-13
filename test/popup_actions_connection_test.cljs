(ns popup-actions-connection-test
  "Tests for popup connection-related action handlers"
  (:require ["vitest" :refer [describe test expect]]
            [popup-actions :as popup-actions]))

;; ============================================================
;; Shared Setup
;; ============================================================

(def initial-state
  {:ports/nrepl "3339"
   :ports/ws "3340"
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
   :settings/default-origins []
   :settings/default-nrepl-port "3339"
   :settings/default-ws-port "3340"})

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
        (.toBe "3340"))))

;; Copy command

(defn- test-copy-command-generates-with-ports []
  (let [result (popup-actions/handle-action initial-state uf-data [:popup/ax.copy-command])
        [fx-name cmd] (first (:uf/fxs result))]
    (-> (expect fx-name)
        (.toBe :popup/fx.copy-command))
    ;; Command should contain the ports
    (-> (expect (.includes cmd "3339"))
        (.toBe true))
    (-> (expect (.includes cmd "3340"))
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
        (.toBe 3340))))

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
        (.toBe "3340"))))

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

;; Default port settings

(defn- test-set-default-nrepl-port-updates-and-saves []
  (let [result (popup-actions/handle-action initial-state uf-data [:popup/ax.set-default-nrepl-port "12345"])]
    (-> (expect (:settings/default-nrepl-port (:uf/db result)))
        (.toBe "12345"))
    (-> (expect (first (first (:uf/fxs result))))
        (.toBe :popup/fx.save-default-ports-setting))))

(defn- test-set-default-ws-port-updates-and-saves []
  (let [result (popup-actions/handle-action initial-state uf-data [:popup/ax.set-default-ws-port "12346"])]
    (-> (expect (:settings/default-ws-port (:uf/db result)))
        (.toBe "12346"))
    (-> (expect (first (first (:uf/fxs result))))
        (.toBe :popup/fx.save-default-ports-setting))))

(defn- test-set-default-nrepl-port-preserves-other-default []
  (let [result (popup-actions/handle-action initial-state uf-data [:popup/ax.set-default-nrepl-port "9999"])
        [_fx-name ports] (first (:uf/fxs result))]
    (-> (expect (:settings/default-nrepl-port ports))
        (.toBe "9999"))
    (-> (expect (:settings/default-ws-port ports))
        (.toBe "3340"))))

(defn- test-load-default-ports-triggers-effect []
  (let [result (popup-actions/handle-action initial-state uf-data [:popup/ax.load-default-ports-setting])]
    (-> (expect (first (first (:uf/fxs result))))
        (.toBe :popup/fx.load-default-ports-setting))))

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
            (test ":popup/ax.load-current-url triggers effect" test-load-current-url-triggers-effect)

            ;; Default port settings
            (test ":popup/ax.set-default-nrepl-port updates and saves" test-set-default-nrepl-port-updates-and-saves)
            (test ":popup/ax.set-default-ws-port updates and saves" test-set-default-ws-port-updates-and-saves)
            (test ":popup/ax.set-default-nrepl-port preserves other default" test-set-default-nrepl-port-preserves-other-default)
            (test ":popup/ax.load-default-ports-setting triggers effect" test-load-default-ports-triggers-effect)))

;; ============================================================
;; normalize-domain-ports tests
;; ============================================================

(defn- test-normalize-no-domain-entry []
  (let [defaults {:nrepl "1339" :ws "1340"}
        result (popup-actions/normalize-domain-ports defaults nil)]
    (-> (expect (:nrepl (:effective-ports result))) (.toBe "1339"))
    (-> (expect (:ws (:effective-ports result))) (.toBe "1340"))
    (-> (expect (:persist? result)) (.toBe false))
    (-> (expect (:normalized-domain-ports result)) (.toBeNull))
    (-> (expect (:nrepl (:source result))) (.toBe :default))
    (-> (expect (:ws (:source result))) (.toBe :default))))

(defn- test-normalize-domain-differs-from-defaults []
  (let [defaults {:nrepl "1339" :ws "1340"}
        domain-ports {:nrepl "5678" :ws "5679"}
        result (popup-actions/normalize-domain-ports defaults domain-ports)]
    (-> (expect (:nrepl (:effective-ports result))) (.toBe "5678"))
    (-> (expect (:ws (:effective-ports result))) (.toBe "5679"))
    (-> (expect (:persist? result)) (.toBe true))
    (-> (expect (:nrepl (:normalized-domain-ports result))) (.toBe "5678"))
    (-> (expect (:ws (:normalized-domain-ports result))) (.toBe "5679"))
    (-> (expect (:nrepl (:source result))) (.toBe :override))
    (-> (expect (:ws (:source result))) (.toBe :override))))

(defn- test-normalize-domain-equals-defaults []
  (let [defaults {:nrepl "1339" :ws "1340"}
        domain-ports {:nrepl "1339" :ws "1340"}
        result (popup-actions/normalize-domain-ports defaults domain-ports)]
    (-> (expect (:nrepl (:effective-ports result))) (.toBe "1339"))
    (-> (expect (:ws (:effective-ports result))) (.toBe "1340"))
    (-> (expect (:persist? result)) (.toBe false))
    (-> (expect (:normalized-domain-ports result)) (.toBeNull))
    (-> (expect (:nrepl (:source result))) (.toBe :default))
    (-> (expect (:ws (:source result))) (.toBe :default))))

(defn- test-normalize-partial-domain-entry []
  (let [defaults {:nrepl "1339" :ws "1340"}
        domain-ports {:nrepl "5678"}
        result (popup-actions/normalize-domain-ports defaults domain-ports)]
    (-> (expect (:nrepl (:effective-ports result))) (.toBe "5678"))
    (-> (expect (:ws (:effective-ports result))) (.toBe "1340"))
    (-> (expect (:persist? result)) (.toBe true))
    (-> (expect (:nrepl (:normalized-domain-ports result))) (.toBe "5678"))
    (-> (expect (:ws (:normalized-domain-ports result))) (.toBe "1340"))
    (-> (expect (:nrepl (:source result))) (.toBe :override))
    (-> (expect (:ws (:source result))) (.toBe :default))))

(describe "normalize-domain-ports"
          (fn []
            (test "no domain entry uses defaults, persist? false"
                  test-normalize-no-domain-entry)
            (test "domain differs from defaults uses domain values, persist? true"
                  test-normalize-domain-differs-from-defaults)
            (test "domain equals defaults clears redundant, persist? false"
                  test-normalize-domain-equals-defaults)
            (test "partial domain entry fills missing from defaults"
                  test-normalize-partial-domain-entry)))

;; ============================================================
;; init-ports (consolidated startup) tests
;; ============================================================

(defn- test-init-ports-triggers-effect []
  (let [result (popup-actions/handle-action initial-state uf-data [:popup/ax.init-ports])]
    (-> (expect (first (first (:uf/fxs result))))
        (.toBe :popup/fx.init-ports))))

(defn- test-apply-init-ports-fresh-install []
  ;; No stored defaults, no domain ports => hardcoded fallbacks
  (let [result (popup-actions/handle-action initial-state uf-data
                 [:popup/ax.apply-init-ports {:stored-defaults nil
                                              :domain-ports nil}])
        db (:uf/db result)]
    (-> (expect (:settings/default-nrepl-port db)) (.toBe "3339"))
    (-> (expect (:settings/default-ws-port db)) (.toBe "3340"))
    (-> (expect (:ports/nrepl db)) (.toBe "3339"))
    (-> (expect (:ports/ws db)) (.toBe "3340"))))

(defn- test-apply-init-ports-stored-defaults-no-domain []
  ;; Stored defaults "5555"/"5556", no domain override => uses stored defaults
  (let [result (popup-actions/handle-action initial-state uf-data
                 [:popup/ax.apply-init-ports {:stored-defaults {:nrepl "5555" :ws "5556"}
                                              :domain-ports nil}])
        db (:uf/db result)]
    (-> (expect (:settings/default-nrepl-port db)) (.toBe "5555"))
    (-> (expect (:settings/default-ws-port db)) (.toBe "5556"))
    (-> (expect (:ports/nrepl db)) (.toBe "5555"))
    (-> (expect (:ports/ws db)) (.toBe "5556"))))

(defn- test-apply-init-ports-stored-defaults-with-domain-override []
  ;; Stored defaults "5555"/"5556", domain override "7777"/"7778"
  (let [result (popup-actions/handle-action initial-state uf-data
                 [:popup/ax.apply-init-ports {:stored-defaults {:nrepl "5555" :ws "5556"}
                                              :domain-ports {:nrepl "7777" :ws "7778"}}])
        db (:uf/db result)]
    ;; Settings still reflect the stored defaults
    (-> (expect (:settings/default-nrepl-port db)) (.toBe "5555"))
    (-> (expect (:settings/default-ws-port db)) (.toBe "5556"))
    ;; Effective ports use domain override
    (-> (expect (:ports/nrepl db)) (.toBe "7777"))
    (-> (expect (:ports/ws db)) (.toBe "7778"))))

(defn- test-apply-init-ports-sets-source-default []
  ;; No domain override => source is both :default
  (let [result (popup-actions/handle-action initial-state uf-data
                 [:popup/ax.apply-init-ports {:stored-defaults {:nrepl "5555" :ws "5556"}
                                              :domain-ports nil}])
        db (:uf/db result)]
    (-> (expect (:nrepl (:ports/source db))) (.toBe :default))
    (-> (expect (:ws (:ports/source db))) (.toBe :default))))

(defn- test-apply-init-ports-sets-source-override []
  ;; Domain override => source reflects overrides
  (let [result (popup-actions/handle-action initial-state uf-data
                 [:popup/ax.apply-init-ports {:stored-defaults {:nrepl "5555" :ws "5556"}
                                              :domain-ports {:nrepl "7777" :ws "7778"}}])
        db (:uf/db result)]
    (-> (expect (:nrepl (:ports/source db))) (.toBe :override))
    (-> (expect (:ws (:ports/source db))) (.toBe :override))))

(describe "init-ports (consolidated startup)"
          (fn []
            (test ":popup/ax.init-ports triggers effect"
                  test-init-ports-triggers-effect)
            (test ":popup/ax.apply-init-ports fresh install uses hardcoded fallbacks"
                  test-apply-init-ports-fresh-install)
            (test ":popup/ax.apply-init-ports uses stored defaults when no domain override"
                  test-apply-init-ports-stored-defaults-no-domain)
            (test ":popup/ax.apply-init-ports resolves domain override over stored defaults"
                  test-apply-init-ports-stored-defaults-with-domain-override)
            (test ":popup/ax.apply-init-ports sets :ports/source :default when no override"
                  test-apply-init-ports-sets-source-default)
            (test ":popup/ax.apply-init-ports sets :ports/source :override when overridden"
                  test-apply-init-ports-sets-source-override)))

;; ============================================================
;; Save-path normalization tests (Phase 3)
;; ============================================================

(defn- test-set-nrepl-port-equal-to-default-clears-domain-ports []
  ;; Both ports match defaults -> should clear domain ports, not save
  (let [result (popup-actions/handle-action initial-state uf-data [:popup/ax.set-nrepl-port "3339"])]
    (-> (expect (:ports/nrepl (:uf/db result)))
        (.toBe "3339"))
    (-> (expect (first (first (:uf/fxs result))))
        (.toBe :popup/fx.clear-domain-ports))))

(defn- test-set-ws-port-equal-to-default-clears-domain-ports []
  ;; Both ports match defaults -> should clear domain ports, not save
  (let [result (popup-actions/handle-action initial-state uf-data [:popup/ax.set-ws-port "3340"])]
    (-> (expect (:ports/ws (:uf/db result)))
        (.toBe "3340"))
    (-> (expect (first (first (:uf/fxs result))))
        (.toBe :popup/fx.clear-domain-ports))))

(defn- test-set-nrepl-port-different-from-default-saves []
  ;; nrepl differs from default -> should persist
  (let [result (popup-actions/handle-action initial-state uf-data [:popup/ax.set-nrepl-port "5678"])]
    (-> (expect (:ports/nrepl (:uf/db result)))
        (.toBe "5678"))
    (-> (expect (first (first (:uf/fxs result))))
        (.toBe :popup/fx.save-ports))))

(defn- test-set-ws-port-different-from-default-saves []
  ;; ws differs from default -> should persist
  (let [result (popup-actions/handle-action initial-state uf-data [:popup/ax.set-ws-port "5679"])]
    (-> (expect (:ports/ws (:uf/db result)))
        (.toBe "5679"))
    (-> (expect (first (first (:uf/fxs result))))
        (.toBe :popup/fx.save-ports))))

(defn- test-return-to-default-nrepl-clears-when-both-match []
  ;; State has non-default nrepl, ws is already default
  ;; Setting nrepl back to default -> both match -> clear
  (let [state (assoc initial-state :ports/nrepl "5678")
        result (popup-actions/handle-action state uf-data [:popup/ax.set-nrepl-port "3339"])]
    (-> (expect (:ports/nrepl (:uf/db result)))
        (.toBe "3339"))
    (-> (expect (first (first (:uf/fxs result))))
        (.toBe :popup/fx.clear-domain-ports))))

(defn- test-return-to-default-nrepl-saves-when-ws-differs []
  ;; State has non-default nrepl and non-default ws
  ;; Setting nrepl back to default -> ws still differs -> save
  (let [state (assoc initial-state :ports/nrepl "5678" :ports/ws "5679")
        result (popup-actions/handle-action state uf-data [:popup/ax.set-nrepl-port "3339"])]
    (-> (expect (:ports/nrepl (:uf/db result)))
        (.toBe "3339"))
    (-> (expect (first (first (:uf/fxs result))))
        (.toBe :popup/fx.save-ports))))

(defn- test-set-nrepl-port-updates-source-to-override []
  ;; Setting nrepl to a non-default value should mark nrepl source as :override
  (let [result (popup-actions/handle-action initial-state uf-data [:popup/ax.set-nrepl-port "7777"])
        source (:ports/source (:uf/db result))]
    (-> (expect (:nrepl source)) (.toBe :override))
    (-> (expect (:ws source)) (.toBe :default))))

(defn- test-set-ws-port-updates-source-to-override []
  ;; Setting ws to a non-default value should mark ws source as :override
  (let [result (popup-actions/handle-action initial-state uf-data [:popup/ax.set-ws-port "7778"])
        source (:ports/source (:uf/db result))]
    (-> (expect (:nrepl source)) (.toBe :default))
    (-> (expect (:ws source)) (.toBe :override))))

(defn- test-set-nrepl-port-to-default-marks-source-default []
  ;; Setting nrepl back to default value should mark nrepl source as :default
  (let [result (popup-actions/handle-action initial-state uf-data [:popup/ax.set-nrepl-port "3339"])
        source (:ports/source (:uf/db result))]
    (-> (expect (:nrepl source)) (.toBe :default))
    (-> (expect (:ws source)) (.toBe :default))))

(defn- test-override-sticky-through-default-change []
  ;; Full scenario: set override, then change defaults - override should stick
  (let [;; Step 1: Set explicit override on nrepl port
        step1 (popup-actions/handle-action initial-state uf-data [:popup/ax.set-nrepl-port "7777"])
        ;; Step 2: Change default nrepl port
        step2 (popup-actions/handle-action (:uf/db step1) uf-data [:popup/ax.set-default-nrepl-port "8888"])
        db (:uf/db step2)]
    ;; Override should stick at 7777, not cascade to 8888
    (-> (expect (:ports/nrepl db)) (.toBe "7777"))
    ;; Default should be updated
    (-> (expect (:settings/default-nrepl-port db)) (.toBe "8888"))))

(describe "save-path normalization"
          (fn []
            (test "setting nrepl port equal to default clears domain ports"
                  test-set-nrepl-port-equal-to-default-clears-domain-ports)
            (test "setting ws port equal to default clears domain ports"
                  test-set-ws-port-equal-to-default-clears-domain-ports)
            (test "setting nrepl port different from default saves"
                  test-set-nrepl-port-different-from-default-saves)
            (test "setting ws port different from default saves"
                  test-set-ws-port-different-from-default-saves)
            (test "returning nrepl to default clears when both ports match defaults"
                  test-return-to-default-nrepl-clears-when-both-match)
            (test "returning nrepl to default saves when ws still differs"
                  test-return-to-default-nrepl-saves-when-ws-differs)
            (test "set-nrepl-port updates source to override"
                  test-set-nrepl-port-updates-source-to-override)
            (test "set-ws-port updates source to override"
                  test-set-ws-port-updates-source-to-override)
            (test "set-nrepl-port to default marks source as default"
                  test-set-nrepl-port-to-default-marks-source-default)
            (test "override remains sticky through default change"
                  test-override-sticky-through-default-change)))

;; ============================================================
;; Default ports changed (live reactivity) tests - Phase 4
;; ============================================================

(defn- test-default-change-updates-inherited-domain []
  ;; Domain is using defaults (no override stored)
  ;; When defaults change, effective ports should update
  (let [result (popup-actions/handle-action initial-state uf-data
                 [:popup/ax.on-default-ports-changed {:nrepl "5555" :ws "5556"} nil])
        db (:uf/db result)]
    ;; Settings update to new defaults
    (-> (expect (:settings/default-nrepl-port db)) (.toBe "5555"))
    (-> (expect (:settings/default-ws-port db)) (.toBe "5556"))
    ;; Effective ports cascade to new defaults (no override)
    (-> (expect (:ports/nrepl db)) (.toBe "5555"))
    (-> (expect (:ports/ws db)) (.toBe "5556"))))

(defn- test-default-change-preserves-explicit-override []
  ;; Domain has explicit override "7777"/"7778"
  ;; When defaults change, effective ports should keep the override
  (let [state (assoc initial-state :ports/nrepl "7777" :ports/ws "7778")
        result (popup-actions/handle-action state uf-data
                 [:popup/ax.on-default-ports-changed {:nrepl "5555" :ws "5556"}
                  {:nrepl "7777" :ws "7778"}])
        db (:uf/db result)]
    ;; Settings update to new defaults
    (-> (expect (:settings/default-nrepl-port db)) (.toBe "5555"))
    (-> (expect (:settings/default-ws-port db)) (.toBe "5556"))
    ;; Effective ports keep explicit override
    (-> (expect (:ports/nrepl db)) (.toBe "7777"))
    (-> (expect (:ports/ws db)) (.toBe "7778"))))

(defn- test-default-change-partial-override-keeps-override-cascades-default []
  ;; Domain has override only for nrepl "7777", ws inherits
  ;; When defaults change to "5555"/"5556", nrepl keeps "7777" but ws cascades to "5556"
  (let [state (assoc initial-state :ports/nrepl "7777" :ports/ws "3340")
        result (popup-actions/handle-action state uf-data
                 [:popup/ax.on-default-ports-changed {:nrepl "5555" :ws "5556"}
                  {:nrepl "7777"}])
        db (:uf/db result)]
    ;; Settings update to new defaults
    (-> (expect (:settings/default-nrepl-port db)) (.toBe "5555"))
    (-> (expect (:settings/default-ws-port db)) (.toBe "5556"))
    ;; nrepl keeps explicit override, ws cascades to new default
    (-> (expect (:ports/nrepl db)) (.toBe "7777"))
    (-> (expect (:ports/ws db)) (.toBe "5556"))))

(defn- test-default-change-no-op-when-unchanged []
  ;; Defaults "change" to the same values they already are
  ;; Should return same state reference (unchanged guard) with no effects
  (let [state (assoc initial-state :ports/source {:nrepl :default :ws :default})
        result (popup-actions/handle-action state uf-data
                 [:popup/ax.on-default-ports-changed {:nrepl "3339" :ws "3340"} nil])
        db (:uf/db result)]
    ;; Same reference returned (unchanged guard)
    (-> (expect db) (.toBe state))
    ;; No effects
    (-> (expect (:uf/fxs result)) (.toBeUndefined))))

(defn- test-default-change-sets-source-both-default []
  ;; No domain override => both ports sourced from defaults
  (let [result (popup-actions/handle-action initial-state uf-data
                 [:popup/ax.on-default-ports-changed {:nrepl "5555" :ws "5556"} nil])
        db (:uf/db result)]
    (-> (expect (:nrepl (:ports/source db))) (.toBe :default))
    (-> (expect (:ws (:ports/source db))) (.toBe :default))))

(defn- test-default-change-sets-source-with-overrides []
  ;; Both ports overridden => both sourced from override
  (let [state (assoc initial-state :ports/nrepl "7777" :ports/ws "7778")
        result (popup-actions/handle-action state uf-data
                 [:popup/ax.on-default-ports-changed {:nrepl "5555" :ws "5556"}
                  {:nrepl "7777" :ws "7778"}])
        db (:uf/db result)]
    (-> (expect (:nrepl (:ports/source db))) (.toBe :override))
    (-> (expect (:ws (:ports/source db))) (.toBe :override))))

(defn- test-default-change-sets-source-partial-override []
  ;; nrepl overridden, ws inherits default
  (let [state (assoc initial-state :ports/nrepl "7777" :ports/ws "3340")
        result (popup-actions/handle-action state uf-data
                 [:popup/ax.on-default-ports-changed {:nrepl "5555" :ws "5556"}
                  {:nrepl "7777"}])
        db (:uf/db result)]
    (-> (expect (:nrepl (:ports/source db))) (.toBe :override))
    (-> (expect (:ws (:ports/source db))) (.toBe :default))))

(defn- test-set-default-nrepl-port-cascades-to-inherited-ports []
  ;; set-default-nrepl-port should cascade to :ports/* when domain uses defaults
  (let [result (popup-actions/handle-action initial-state uf-data
                 [:popup/ax.set-default-nrepl-port "9999"])
        db (:uf/db result)]
    ;; Settings updated
    (-> (expect (:settings/default-nrepl-port db)) (.toBe "9999"))
    ;; Effective ports cascade - nrepl follows new default, ws unchanged
    (-> (expect (:ports/nrepl db)) (.toBe "9999"))
    (-> (expect (:ports/ws db)) (.toBe "3340"))))

(defn- test-set-default-ws-port-cascades-to-inherited-ports []
  ;; set-default-ws-port should cascade to :ports/* when domain uses defaults
  (let [result (popup-actions/handle-action initial-state uf-data
                 [:popup/ax.set-default-ws-port "9999"])
        db (:uf/db result)]
    ;; Settings updated
    (-> (expect (:settings/default-ws-port db)) (.toBe "9999"))
    ;; Effective ports cascade - ws follows new default, nrepl unchanged
    (-> (expect (:ports/nrepl db)) (.toBe "3339"))
    (-> (expect (:ports/ws db)) (.toBe "9999"))))

(defn- test-set-default-nrepl-port-preserves-explicit-override []
  ;; When domain has explicit port override, changing default should NOT override it
  (let [state (assoc initial-state
                :ports/nrepl "7777" :ports/ws "7778"
                :ports/source {:nrepl :override :ws :override})
        result (popup-actions/handle-action state uf-data
                 [:popup/ax.set-default-nrepl-port "9999"])
        db (:uf/db result)]
    ;; Settings updated
    (-> (expect (:settings/default-nrepl-port db)) (.toBe "9999"))
    ;; Domain ports differ from new defaults, so they stay as overrides
    (-> (expect (:ports/nrepl db)) (.toBe "7777"))
    (-> (expect (:ports/ws db)) (.toBe "7778"))))

(describe "default ports changed (live reactivity)"
          (fn []
            (test "default change updates inherited domain ports"
                  test-default-change-updates-inherited-domain)
            (test "default change preserves explicit override"
                  test-default-change-preserves-explicit-override)
            (test "partial override keeps override, cascades default for other port"
                  test-default-change-partial-override-keeps-override-cascades-default)
            (test "no-op when defaults unchanged"
                  test-default-change-no-op-when-unchanged)
            (test "sets :ports/source both :default when no overrides"
                  test-default-change-sets-source-both-default)
            (test "sets :ports/source both :override when both overridden"
                  test-default-change-sets-source-with-overrides)
            (test "sets :ports/source mixed when partial override"
                  test-default-change-sets-source-partial-override)
            (test "set-default-nrepl-port cascades to inherited ports"
                  test-set-default-nrepl-port-cascades-to-inherited-ports)
            (test "set-default-ws-port cascades to inherited ports"
                  test-set-default-ws-port-cascades-to-inherited-ports)
            (test "set-default-nrepl-port preserves explicit override"
                  test-set-default-nrepl-port-preserves-explicit-override)))

;; ============================================================
;; Port migration cleanup
;; ============================================================

(defn- test-migration-removes-redundant-entries []
  ;; ports_foo.com has same ports as defaults => redundant => remove
  (let [defaults {:nrepl "1339" :ws "1340"}
        storage-data {"ports_foo.com" {:nrepl "1339" :ws "1340"}
                      "ports_bar.com" {:nrepl "1339" :ws "1340"}}
        result (popup-actions/handle-action initial-state uf-data
                 [:popup/ax.apply-port-migration {:defaults defaults
                                                  :port-entries storage-data}])
        fxs (:uf/fxs result)
        remove-fx (first (filter #(= :popup/fx.remove-storage-keys (first %)) fxs))
        marker-fx (first (filter #(= :popup/fx.set-storage-key (first %)) fxs))]
    ;; Should remove both redundant keys
    (-> (expect (set (second remove-fx)))
        (.toEqual (set ["ports_foo.com" "ports_bar.com"])))
    ;; Should set the marker key
    (-> (expect (second marker-fx))
        (.toBe "epupp_migration_ports_normalized_v1"))))

(defn- test-migration-preserves-explicit-overrides []
  ;; ports_custom.com has different ports => keep
  (let [defaults {:nrepl "1339" :ws "1340"}
        storage-data {"ports_custom.com" {:nrepl "9999" :ws "9998"}}
        result (popup-actions/handle-action initial-state uf-data
                 [:popup/ax.apply-port-migration {:defaults defaults
                                                  :port-entries storage-data}])
        fxs (:uf/fxs result)
        remove-fx (first (filter #(= :popup/fx.remove-storage-keys (first %)) fxs))]
    ;; No keys to remove
    (-> (expect (second remove-fx))
        (.toEqual []))))

(defn- test-migration-mixed-redundant-and-overrides []
  ;; Mix of redundant and explicit overrides
  (let [defaults {:nrepl "1339" :ws "1340"}
        storage-data {"ports_default.com" {:nrepl "1339" :ws "1340"}
                      "ports_custom.com" {:nrepl "5555" :ws "5556"}
                      "ports_also-default.com" {:nrepl "1339" :ws "1340"}}
        result (popup-actions/handle-action initial-state uf-data
                 [:popup/ax.apply-port-migration {:defaults defaults
                                                  :port-entries storage-data}])
        fxs (:uf/fxs result)
        remove-fx (first (filter #(= :popup/fx.remove-storage-keys (first %)) fxs))]
    ;; Should only remove redundant keys
    (-> (expect (set (second remove-fx)))
        (.toEqual (set ["ports_default.com" "ports_also-default.com"])))))

(defn- test-migration-sets-marker-key []
  (let [defaults {:nrepl "1339" :ws "1340"}
        storage-data {}
        result (popup-actions/handle-action initial-state uf-data
                 [:popup/ax.apply-port-migration {:defaults defaults
                                                  :port-entries storage-data}])
        fxs (:uf/fxs result)
        marker-fx (first (filter #(= :popup/fx.set-storage-key (first %)) fxs))]
    (-> (expect (second marker-fx))
        (.toBe "epupp_migration_ports_normalized_v1"))
    (-> (expect (nth marker-fx 2))
        (.toBe true))))

(defn- test-migration-handles-empty-storage []
  (let [defaults {:nrepl "1339" :ws "1340"}
        storage-data {}
        result (popup-actions/handle-action initial-state uf-data
                 [:popup/ax.apply-port-migration {:defaults defaults
                                                  :port-entries storage-data}])
        fxs (:uf/fxs result)
        remove-fx (first (filter #(= :popup/fx.remove-storage-keys (first %)) fxs))]
    (-> (expect (second remove-fx))
        (.toEqual []))))

(defn- test-migration-partial-override-kept []
  ;; One port matches default, other is overridden => keep (has real override)
  (let [defaults {:nrepl "1339" :ws "1340"}
        storage-data {"ports_partial.com" {:nrepl "1339" :ws "9999"}}
        result (popup-actions/handle-action initial-state uf-data
                 [:popup/ax.apply-port-migration {:defaults defaults
                                                  :port-entries storage-data}])
        fxs (:uf/fxs result)
        remove-fx (first (filter #(= :popup/fx.remove-storage-keys (first %)) fxs))]
    ;; Should NOT remove - ws port is overridden
    (-> (expect (second remove-fx))
        (.toEqual []))))

(describe "port migration cleanup"
          (fn []
            (test "removes redundant entries matching defaults"
                  test-migration-removes-redundant-entries)
            (test "preserves explicit overrides"
                  test-migration-preserves-explicit-overrides)
            (test "mixed redundant and override entries"
                  test-migration-mixed-redundant-and-overrides)
            (test "sets marker key after completion"
                  test-migration-sets-marker-key)
            (test "handles empty storage gracefully"
                  test-migration-handles-empty-storage)
            (test "keeps entry when only one port is overridden"
                  test-migration-partial-override-kept)))
