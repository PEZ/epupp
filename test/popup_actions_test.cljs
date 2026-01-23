(ns popup-actions-test
  "Tests for popup action handlers - pure state transitions"
  (:require ["vitest" :refer [describe test expect]]
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

(describe "popup port actions"
          (fn []
            (test ":popup/ax.set-nrepl-port updates port and triggers save"
                  (fn []
                    (let [result (popup-actions/handle-action initial-state uf-data [:popup/ax.set-nrepl-port "12345"])]
                      (-> (expect (:ports/nrepl (:uf/db result)))
                          (.toBe "12345"))
              ;; Should trigger save effect
                      (-> (expect (first (first (:uf/fxs result))))
                          (.toBe :popup/fx.save-ports)))))

            (test ":popup/ax.set-ws-port updates port and triggers save"
                  (fn []
                    (let [result (popup-actions/handle-action initial-state uf-data [:popup/ax.set-ws-port "12346"])]
                      (-> (expect (:ports/ws (:uf/db result)))
                          (.toBe "12346"))
              ;; Should trigger save effect
                      (-> (expect (first (first (:uf/fxs result))))
                          (.toBe :popup/fx.save-ports)))))

            (test ":popup/ax.set-nrepl-port preserves other port in save effect"
                  (fn []
                    (let [result (popup-actions/handle-action initial-state uf-data [:popup/ax.set-nrepl-port "9999"])
                          [_fx-name ports] (first (:uf/fxs result))]
                      (-> (expect (:ports/nrepl ports))
                          (.toBe "9999"))
                      (-> (expect (:ports/ws ports))
                          (.toBe "1340")))))))

(describe "popup copy command action"
          (fn []
            (test ":popup/ax.copy-command generates command with ports from state"
                  (fn []
                    (let [result (popup-actions/handle-action initial-state uf-data [:popup/ax.copy-command])
                          [fx-name cmd] (first (:uf/fxs result))]
                      (-> (expect fx-name)
                          (.toBe :popup/fx.copy-command))
              ;; Command should contain the ports
                      (-> (expect (.includes cmd "1339"))
                          (.toBe true))
                      (-> (expect (.includes cmd "1340"))
                          (.toBe true)))))

            (test ":popup/ax.copy-command uses deps-string from uf-data"
                  (fn []
                    (let [custom-uf-data {:config/deps-string "{:deps {foo/bar {:mvn/version \"1.0\"}}}"}
                          result (popup-actions/handle-action initial-state custom-uf-data [:popup/ax.copy-command])
                          [_fx-name cmd] (first (:uf/fxs result))]
                      (-> (expect (.includes cmd "foo/bar"))
                          (.toBe true)))))))

(describe "popup connect action"
          (fn []
            (test ":popup/ax.connect triggers connect effect (status handled by effect)"
                  (fn []
                    (let [result (popup-actions/handle-action initial-state uf-data [:popup/ax.connect])]
                      ;; Action only triggers effect - status is set by the effect itself
                      (-> (expect (first (first (:uf/fxs result))))
                          (.toBe :popup/fx.connect)))))

            (test ":popup/ax.connect passes parsed port to effect"
                  (fn []
                    (let [result (popup-actions/handle-action initial-state uf-data [:popup/ax.connect])
                          [_fx-name port] (first (:uf/fxs result))]
                      (-> (expect port)
                          (.toBe 1340)))))

            (test ":popup/ax.connect returns nil for invalid port"
                  (fn []
                    (let [state (assoc initial-state :ports/ws "invalid")
                          result (popup-actions/handle-action state uf-data [:popup/ax.connect])]
                      (-> (expect result)
                          (.toBeUndefined)))))

            (test ":popup/ax.connect returns nil for out-of-range port"
                  (fn []
                    (let [state (assoc initial-state :ports/ws "70000")
                          result (popup-actions/handle-action state uf-data [:popup/ax.connect])]
                      (-> (expect result)
                          (.toBeUndefined)))))))

(describe "popup load actions"
          (fn []
            (test ":popup/ax.check-status triggers effect with ws port"
                  (fn []
                    (let [result (popup-actions/handle-action initial-state uf-data [:popup/ax.check-status])
                          [fx-name ws-port] (first (:uf/fxs result))]
                      (-> (expect fx-name)
                          (.toBe :popup/fx.check-status))
                      (-> (expect ws-port)
                          (.toBe "1340")))))

            (test ":popup/ax.load-saved-ports triggers effect"
                  (fn []
                    (let [result (popup-actions/handle-action initial-state uf-data [:popup/ax.load-saved-ports])]
                      (-> (expect (first (first (:uf/fxs result))))
                          (.toBe :popup/fx.load-saved-ports)))))

            (test ":popup/ax.load-scripts triggers effect"
                  (fn []
                    (let [result (popup-actions/handle-action initial-state uf-data [:popup/ax.load-scripts])]
                      (-> (expect (first (first (:uf/fxs result))))
                          (.toBe :popup/fx.load-scripts)))))

            (test ":popup/ax.load-current-url triggers effect"
                  (fn []
                    (let [result (popup-actions/handle-action initial-state uf-data [:popup/ax.load-current-url])]
                      (-> (expect (first (first (:uf/fxs result))))
                          (.toBe :popup/fx.load-current-url)))))))

(describe "popup script actions"
          (fn []
            (test ":popup/ax.toggle-script passes scripts and id to effect"
                  (fn []
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
                          (.toBe "*://example.com/*")))))

            (test ":popup/ax.delete-script removes from list and triggers effect"
                  (fn []
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
                          (.toBe "test-1")))))))

(describe "popup inspect action"
          (fn []
            (test ":popup/ax.inspect-script triggers inspect effect and schedules banner"
                  (fn []
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
                            (.toBe "info"))))))

            (test ":popup/ax.inspect-script passes script to effect"
                  (fn []
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
                          (.toBe "Test Script")))))

            (test ":popup/ax.inspect-script returns nil for non-existent script"
                  (fn []
                    (let [scripts [{:script/id "other"}]
                          state (assoc initial-state :scripts/list scripts)
                          result (popup-actions/handle-action state uf-data [:popup/ax.inspect-script "missing"])]
                      (-> (expect result)
                          (.toBeUndefined)))))))

(describe "popup evaluate action"
          (fn []
            (test ":popup/ax.evaluate-script triggers evaluate effect"
                  (fn []
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
                          (.toBe "(println \"hi\")")))))

            (test ":popup/ax.evaluate-script returns nil for non-existent script"
                  (fn []
                    (let [scripts [{:script/id "other"}]
                          state (assoc initial-state :scripts/list scripts)
                          result (popup-actions/handle-action state uf-data [:popup/ax.evaluate-script "missing"])]
                      (-> (expect result)
                          (.toBeUndefined)))))))

(describe "popup settings view actions"
          (fn []
            (test ":popup/ax.load-user-origins triggers effect"
                  (fn []
                    (let [result (popup-actions/handle-action initial-state uf-data [:popup/ax.load-user-origins])]
                      (-> (expect (first (first (:uf/fxs result))))
                          (.toBe :popup/fx.load-user-origins)))))

            (test ":popup/ax.set-new-origin updates input value"
                  (fn []
                    (let [result (popup-actions/handle-action initial-state uf-data [:popup/ax.set-new-origin "https://example.com/"])]
                      (-> (expect (:settings/new-origin (:uf/db result)))
                          (.toBe "https://example.com/")))))))

(describe "popup origin add/remove actions"
          (fn []
            (test ":popup/ax.add-origin adds valid origin and clears input"
                  (fn []
                    (let [state (-> initial-state
                                    (assoc :settings/new-origin "https://git.example.com/")
                                    (assoc :settings/user-origins [])
                                    (assoc :settings/default-origins []))
                          result (popup-actions/handle-action state uf-data [:popup/ax.add-origin])]
              ;; Should update state
                      (-> (expect (first (:settings/user-origins (:uf/db result))))
                          (.toBe "https://git.example.com/"))
              ;; Should clear input
                      (-> (expect (:settings/new-origin (:uf/db result)))
                          (.toBe ""))
              ;; Should trigger add effect
                      (let [[fx-name origin] (first (:uf/fxs result))]
                        (-> (expect fx-name)
                            (.toBe :popup/fx.add-user-origin))
                        (-> (expect origin)
                            (.toBe "https://git.example.com/"))))))

            (test ":popup/ax.add-origin rejects invalid origin (no trailing slash)"
                  (fn []
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
                          (.toBe "Must start with http:// or https:// and end with / or :"))))))

            (test ":popup/ax.add-origin rejects invalid origin (wrong protocol)"
                  (fn []
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
                          (.toBe "error")))))

            (test ":popup/ax.add-origin rejects duplicate origin in user list"
                  (fn []
                    (let [state (-> initial-state
                                    (assoc :settings/new-origin "https://existing.com/")
                                    (assoc :settings/user-origins ["https://existing.com/"])
                                    (assoc :settings/default-origins []))
                          result (popup-actions/handle-action state uf-data [:popup/ax.add-origin])
                          [action event-type message _] (first (:uf/dxs result))]
              ;; Should dispatch error banner via :uf/dxs
                      (-> (expect action)
                          (.toBe :popup/ax.show-system-banner))
                      (-> (expect event-type)
                          (.toBe "error"))
                      (-> (expect message)
                          (.toBe "Origin already exists")))))

            (test ":popup/ax.add-origin rejects duplicate origin in default list"
                  (fn []
                    (let [state (-> initial-state
                                    (assoc :settings/new-origin "https://github.com/")
                                    (assoc :settings/user-origins [])
                                    (assoc :settings/default-origins ["https://github.com/"]))
                          result (popup-actions/handle-action state uf-data [:popup/ax.add-origin])
                          ;; Should dispatch error banner via :uf/dxs
                          [action event-type message _] (first (:uf/dxs result))]
                      (-> (expect action)
                          (.toBe :popup/ax.show-system-banner))
                      (-> (expect event-type)
                          (.toBe "error"))
                      (-> (expect message)
                          (.toBe "Origin already exists"))))

            (test ":popup/ax.remove-origin removes from list and triggers effect"
                  (fn []
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
                          (.toBe "https://a.com/")))))))

(describe "popup section toggle actions"
          (fn []
            (test ":popup/ax.toggle-section toggles collapsed state from false to true"
                  (fn []
                    (let [state {:ui/sections-collapsed {:repl-connect false}}
                          result (popup-actions/handle-action state uf-data [:popup/ax.toggle-section :repl-connect])]
                      (-> (expect (get-in (:uf/db result) [:ui/sections-collapsed :repl-connect]))
                          (.toBe true)))))

            (test ":popup/ax.toggle-section toggles collapsed state from true to false"
                  (fn []
                    (let [state {:ui/sections-collapsed {:settings true}}
                          result (popup-actions/handle-action state uf-data [:popup/ax.toggle-section :settings])]
                      (-> (expect (get-in (:uf/db result) [:ui/sections-collapsed :settings]))
                          (.toBe false)))))

            (test ":popup/ax.toggle-section handles nil state (falsy becomes truthy)"
                  (fn []
                    (let [state {:ui/sections-collapsed {}}
                          result (popup-actions/handle-action state uf-data [:popup/ax.toggle-section :scripts])]
                      (-> (expect (get-in (:uf/db result) [:ui/sections-collapsed :scripts]))
                          (.toBe true)))))))

(describe "popup auto-reconnect actions"
          (fn []
            (test ":popup/ax.load-auto-reconnect-setting triggers effect"
                  (fn []
                    (let [result (popup-actions/handle-action initial-state uf-data [:popup/ax.load-auto-reconnect-setting])]
                      (-> (expect (first (first (:uf/fxs result))))
                          (.toBe :popup/fx.load-auto-reconnect-setting)))))

            (test ":popup/ax.toggle-auto-reconnect-repl toggles from true to false"
                  (fn []
                    (let [state (assoc initial-state :settings/auto-reconnect-repl true)
                          result (popup-actions/handle-action state uf-data [:popup/ax.toggle-auto-reconnect-repl])]
                      (-> (expect (:settings/auto-reconnect-repl (:uf/db result)))
                          (.toBe false))
                      ;; Should trigger save effect
                      (let [[fx-name enabled] (first (:uf/fxs result))]
                        (-> (expect fx-name)
                            (.toBe :popup/fx.save-auto-reconnect-setting))
                        (-> (expect enabled)
                            (.toBe false))))))

            (test ":popup/ax.toggle-auto-reconnect-repl toggles from false to true"
                  (fn []
                    (let [state (assoc initial-state :settings/auto-reconnect-repl false)
                          result (popup-actions/handle-action state uf-data [:popup/ax.toggle-auto-reconnect-repl])]
                      (-> (expect (:settings/auto-reconnect-repl (:uf/db result)))
                          (.toBe true))
                      ;; Should trigger save effect
                      (let [[fx-name enabled] (first (:uf/fxs result))]
                        (-> (expect fx-name)
                            (.toBe :popup/fx.save-auto-reconnect-setting))
                        (-> (expect enabled)
                            (.toBe true))))))))

(describe "shadow list sync actions"
          (fn []
            (test ":ui/ax.sync-scripts-shadow updates content without setting entering flag"
                  (fn []
                    ;; Scenario: script content changed, but it's the same script (no add/remove)
                    ;; The watcher fires with added-items: [] and removed-ids: #{}
                    ;; The sync handler should update the :item content without animation flags
                    (let [state {:scripts/list [{:script/id "test-1" :script/code "updated code"}]
                                 :ui/scripts-shadow [{:item {:script/id "test-1" :script/code "old code"}
                                                      :ui/entering? false
                                                      :ui/leaving? false}]}
                          ;; Content change signal: no membership changes
                          result (popup-actions/handle-action state uf-data
                                                              [:ui/ax.sync-scripts-shadow {:added-items []
                                                                                           :removed-ids #{}}])
                          updated-shadow (:ui/scripts-shadow (:uf/db result))
                          shadow-item (first updated-shadow)]
                      ;; Content should be updated
                      (-> (expect (get-in shadow-item [:item :script/code]))
                          (.toBe "updated code"))
                      ;; Animation flags should remain false (no entering animation)
                      (-> (expect (:ui/entering? shadow-item))
                          (.toBe false))
                      (-> (expect (:ui/leaving? shadow-item))
                          (.toBe false)))))

            (test ":ui/ax.sync-scripts-shadow adds new items with entering flag"
                  (fn []
                    ;; Scenario: a new script was added
                    (let [new-script {:script/id "new-1" :script/code "new code"}
                          state {:scripts/list [new-script]
                                 :ui/scripts-shadow []}
                          result (popup-actions/handle-action state uf-data
                                                              [:ui/ax.sync-scripts-shadow {:added-items [new-script]
                                                                                           :removed-ids #{}}])
                          updated-shadow (:ui/scripts-shadow (:uf/db result))
                          shadow-item (first updated-shadow)]
                      ;; Item should be added
                      (-> (expect (get-in shadow-item [:item :script/id]))
                          (.toBe "new-1"))
                      ;; Should have entering flag for animation
                      (-> (expect (:ui/entering? shadow-item))
                          (.toBe true))
                      (-> (expect (:ui/leaving? shadow-item))
                          (.toBe false)))))

            (test ":ui/ax.sync-scripts-shadow marks removed items as leaving"
                  (fn []
                    ;; Scenario: a script was deleted
                    (let [state {:scripts/list []
                                 :ui/scripts-shadow [{:item {:script/id "to-remove"}
                                                      :ui/entering? false
                                                      :ui/leaving? false}]}
                          result (popup-actions/handle-action state uf-data
                                                              [:ui/ax.sync-scripts-shadow {:added-items []
                                                                                           :removed-ids #{"to-remove"}}])
                          updated-shadow (:ui/scripts-shadow (:uf/db result))
                          shadow-item (first updated-shadow)]
                      ;; Should be marked as leaving for animation
                      (-> (expect (:ui/leaving? shadow-item))
                          (.toBe true)))))))
