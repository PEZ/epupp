(ns popup-actions-test
  "Tests for popup action handlers - pure state transitions"
  (:require ["vitest" :as vt]
            [popup-actions :as popup-actions]))

;; ============================================================
;; Popup Action Tests
;; ============================================================

(def initial-state
  {:ports/nrepl "1339"
   :ports/ws "1340"
   :ui/status nil
   :ui/copy-feedback nil
   :ui/has-connected false
   :ui/editing-hint-script-id nil
   :browser/brave? false
   :scripts/list []
   :scripts/current-url nil})

(def uf-data {:system/now 1234567890
              :config/deps-string "{:deps {}}"})

(vt/describe "popup port actions"
  (fn []
    (vt/test ":popup/ax.set-nrepl-port updates port and triggers save"
          (fn []
            (let [result (popup-actions/handle-action initial-state uf-data [:popup/ax.set-nrepl-port "12345"])]
              (-> (vt/expect (:ports/nrepl (:uf/db result)))
                  (.toBe "12345"))
              ;; Should trigger save effect
              (-> (vt/expect (first (first (:uf/fxs result))))
                  (.toBe :popup/fx.save-ports)))))

    (vt/test ":popup/ax.set-ws-port updates port and triggers save"
          (fn []
            (let [result (popup-actions/handle-action initial-state uf-data [:popup/ax.set-ws-port "12346"])]
              (-> (vt/expect (:ports/ws (:uf/db result)))
                  (.toBe "12346"))
              ;; Should trigger save effect
              (-> (vt/expect (first (first (:uf/fxs result))))
                  (.toBe :popup/fx.save-ports)))))

    (vt/test ":popup/ax.set-nrepl-port preserves other port in save effect"
          (fn []
            (let [result (popup-actions/handle-action initial-state uf-data [:popup/ax.set-nrepl-port "9999"])
                  [_fx-name ports] (first (:uf/fxs result))]
              (-> (vt/expect (:ports/nrepl ports))
                  (.toBe "9999"))
              (-> (vt/expect (:ports/ws ports))
                  (.toBe "1340")))))))

(vt/describe "popup copy command action"
  (fn []
    (vt/test ":popup/ax.copy-command generates command with ports from state"
          (fn []
            (let [result (popup-actions/handle-action initial-state uf-data [:popup/ax.copy-command])
                  [fx-name cmd] (first (:uf/fxs result))]
              (-> (vt/expect fx-name)
                  (.toBe :popup/fx.copy-command))
              ;; Command should contain the ports
              (-> (vt/expect (.includes cmd "1339"))
                  (.toBe true))
              (-> (vt/expect (.includes cmd "1340"))
                  (.toBe true)))))

    (vt/test ":popup/ax.copy-command uses deps-string from uf-data"
          (fn []
            (let [custom-uf-data {:config/deps-string "{:deps {foo/bar {:mvn/version \"1.0\"}}}"}
                  result (popup-actions/handle-action initial-state custom-uf-data [:popup/ax.copy-command])
                  [_fx-name cmd] (first (:uf/fxs result))]
              (-> (vt/expect (.includes cmd "foo/bar"))
                  (.toBe true)))))))

(vt/describe "popup connect action"
  (fn []
    (vt/test ":popup/ax.connect sets status and triggers connect effect"
          (fn []
            (let [result (popup-actions/handle-action initial-state uf-data [:popup/ax.connect])]
              (-> (vt/expect (:ui/status (:uf/db result)))
                  (.toBe "Checking..."))
              (-> (vt/expect (first (first (:uf/fxs result))))
                  (.toBe :popup/fx.connect)))))

    (vt/test ":popup/ax.connect passes parsed port to effect"
          (fn []
            (let [result (popup-actions/handle-action initial-state uf-data [:popup/ax.connect])
                  [_fx-name port] (first (:uf/fxs result))]
              (-> (vt/expect port)
                  (.toBe 1340)))))

    (vt/test ":popup/ax.connect returns nil for invalid port"
          (fn []
            (let [state (assoc initial-state :ports/ws "invalid")
                  result (popup-actions/handle-action state uf-data [:popup/ax.connect])]
              (-> (vt/expect result)
                  (.toBeUndefined)))))

    (vt/test ":popup/ax.connect returns nil for out-of-range port"
          (fn []
            (let [state (assoc initial-state :ports/ws "99999")
                  result (popup-actions/handle-action state uf-data [:popup/ax.connect])]
              (-> (vt/expect result)
                  (.toBeUndefined)))))))

(vt/describe "popup load actions"
  (fn []
    (vt/test ":popup/ax.check-status triggers effect with ws port"
          (fn []
            (let [result (popup-actions/handle-action initial-state uf-data [:popup/ax.check-status])
                  [fx-name ws-port] (first (:uf/fxs result))]
              (-> (vt/expect fx-name)
                  (.toBe :popup/fx.check-status))
              (-> (vt/expect ws-port)
                  (.toBe "1340")))))

    (vt/test ":popup/ax.load-saved-ports triggers effect"
          (fn []
            (let [result (popup-actions/handle-action initial-state uf-data [:popup/ax.load-saved-ports])]
              (-> (vt/expect (first (first (:uf/fxs result))))
                  (.toBe :popup/fx.load-saved-ports)))))

    (vt/test ":popup/ax.load-scripts triggers effect"
          (fn []
            (let [result (popup-actions/handle-action initial-state uf-data [:popup/ax.load-scripts])]
              (-> (vt/expect (first (first (:uf/fxs result))))
                  (.toBe :popup/fx.load-scripts)))))

    (vt/test ":popup/ax.load-current-url triggers effect"
          (fn []
            (let [result (popup-actions/handle-action initial-state uf-data [:popup/ax.load-current-url])]
              (-> (vt/expect (first (first (:uf/fxs result))))
                  (.toBe :popup/fx.load-current-url)))))))

(vt/describe "popup script actions"
  (fn []
    (vt/test ":popup/ax.toggle-script passes scripts and id to effect"
          (fn []
            (let [scripts [{:script/id "test-1" :script/enabled true}]
                  state (assoc initial-state :scripts/list scripts)
                  result (popup-actions/handle-action state uf-data [:popup/ax.toggle-script "test-1" "*://example.com/*"])
                  [fx-name fx-scripts fx-id fx-pattern] (first (:uf/fxs result))]
              (-> (vt/expect fx-name)
                  (.toBe :popup/fx.toggle-script))
              (-> (vt/expect (count fx-scripts))
                  (.toBe 1))
              (-> (vt/expect fx-id)
                  (.toBe "test-1"))
              (-> (vt/expect fx-pattern)
                  (.toBe "*://example.com/*")))))

    (vt/test ":popup/ax.delete-script passes scripts and id to effect"
          (fn []
            (let [scripts [{:script/id "test-1"}]
                  state (assoc initial-state :scripts/list scripts)
                  result (popup-actions/handle-action state uf-data [:popup/ax.delete-script "test-1"])
                  [fx-name _fx-scripts fx-id] (first (:uf/fxs result))]
              (-> (vt/expect fx-name)
                  (.toBe :popup/fx.delete-script))
              (-> (vt/expect fx-id)
                  (.toBe "test-1")))))

    (vt/test ":popup/ax.approve-script passes scripts, id, and pattern to effect"
          (fn []
            (let [scripts [{:script/id "test-1" :script/approved-patterns []}]
                  state (assoc initial-state :scripts/list scripts)
                  result (popup-actions/handle-action state uf-data [:popup/ax.approve-script "test-1" "*://github.com/*"])
                  [fx-name _fx-scripts fx-id fx-pattern] (first (:uf/fxs result))]
              (-> (vt/expect fx-name)
                  (.toBe :popup/fx.approve-script))
              (-> (vt/expect fx-id)
                  (.toBe "test-1"))
              (-> (vt/expect fx-pattern)
                  (.toBe "*://github.com/*")))))

    (vt/test ":popup/ax.deny-script passes scripts and id to effect"
          (fn []
            (let [scripts [{:script/id "test-1" :script/enabled true}]
                  state (assoc initial-state :scripts/list scripts)
                  result (popup-actions/handle-action state uf-data [:popup/ax.deny-script "test-1"])
                  [fx-name _fx-scripts fx-id] (first (:uf/fxs result))]
              (-> (vt/expect fx-name)
                  (.toBe :popup/fx.deny-script))
              (-> (vt/expect fx-id)
                  (.toBe "test-1")))))))

(vt/describe "popup edit action"
  (fn []
    (vt/test ":popup/ax.edit-script sets hint and triggers edit effect"
          (fn []
            (let [scripts [{:script/id "test-1"
                            :script/name "Test"
                            :script/match ["*://example.com/*"]
                            :script/code "(println \"hi\")"}]
                  state (assoc initial-state :scripts/list scripts)
                  result (popup-actions/handle-action state uf-data [:popup/ax.edit-script "test-1"])]
              ;; Should set editing hint
              (-> (vt/expect (:ui/editing-hint-script-id (:uf/db result)))
                  (.toBe "test-1"))
              ;; Should trigger edit effect
              (-> (vt/expect (first (first (:uf/fxs result))))
                  (.toBe :popup/fx.edit-script)))))

    (vt/test ":popup/ax.edit-script passes script to effect"
          (fn []
            (let [scripts [{:script/id "test-1"
                            :script/name "Test Script"
                            :script/match ["*://example.com/*"]
                            :script/code "(println \"hello\")"}]
                  state (assoc initial-state :scripts/list scripts)
                  result (popup-actions/handle-action state uf-data [:popup/ax.edit-script "test-1"])
                  [_fx-name script] (first (:uf/fxs result))]
              (-> (vt/expect (:script/id script))
                  (.toBe "test-1"))
              (-> (vt/expect (:script/name script))
                  (.toBe "Test Script")))))

    (vt/test ":popup/ax.edit-script schedules hint clear"
          (fn []
            (let [scripts [{:script/id "test-1"
                            :script/name "Test"
                            :script/match ["*://example.com/*"]
                            :script/code ""}]
                  state (assoc initial-state :scripts/list scripts)
                  result (popup-actions/handle-action state uf-data [:popup/ax.edit-script "test-1"])
                  ;; Second effect should be defer-dispatch
                  [fx-name] (second (:uf/fxs result))]
              (-> (vt/expect fx-name)
                  (.toBe :uf/fx.defer-dispatch)))))

    (vt/test ":popup/ax.edit-script returns nil for non-existent script"
          (fn []
            (let [scripts [{:script/id "other"}]
                  state (assoc initial-state :scripts/list scripts)
                  result (popup-actions/handle-action state uf-data [:popup/ax.edit-script "missing"])]
              (-> (vt/expect result)
                  (.toBeUndefined)))))))
