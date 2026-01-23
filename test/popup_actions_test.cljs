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

;; Settings view actions

(defn- test-load-user-origins-triggers-effect []
  (let [result (popup-actions/handle-action initial-state uf-data [:popup/ax.load-user-origins])]
    (-> (expect (first (first (:uf/fxs result))))
        (.toBe :popup/fx.load-user-origins))))

(defn- test-set-new-origin-updates-input []
  (let [result (popup-actions/handle-action initial-state uf-data [:popup/ax.set-new-origin "https://example.com/"])]
    (-> (expect (:settings/new-origin (:uf/db result)))
        (.toBe "https://example.com/"))))

(defn- test-add-origin-adds-valid-and-clears []
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
          (.toBe "https://git.example.com/")))))

(defn- test-add-origin-rejects-invalid-no-trailing-slash []
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
        (.toBe "Must start with http:// or https:// and end with / or :"))))

(defn- test-add-origin-rejects-invalid-wrong-protocol []
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

(defn- test-add-origin-rejects-duplicate-in-user-list []
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
        (.toBe "Origin already exists"))))

(defn- test-add-origin-rejects-duplicate-in-default-list []
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

(defn- test-remove-origin-removes-and-triggers-effect []
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

(defn- test-toggle-section-toggles-false-to-true []
  (let [state {:ui/sections-collapsed {:repl-connect false}}
        result (popup-actions/handle-action state uf-data [:popup/ax.toggle-section :repl-connect])]
    (-> (expect (get-in (:uf/db result) [:ui/sections-collapsed :repl-connect]))
        (.toBe true))))

(defn- test-toggle-section-toggles-true-to-false []
  (let [state {:ui/sections-collapsed {:settings true}}
        result (popup-actions/handle-action state uf-data [:popup/ax.toggle-section :settings])]
    (-> (expect (get-in (:uf/db result) [:ui/sections-collapsed :settings]))
        (.toBe false))))

(defn- test-toggle-section-handles-nil-state []
  (let [state {:ui/sections-collapsed {}}
        result (popup-actions/handle-action state uf-data [:popup/ax.toggle-section :scripts])]
    (-> (expect (get-in (:uf/db result) [:ui/sections-collapsed :scripts]))
        (.toBe true))))

(defn- test-load-auto-reconnect-setting-triggers-effect []
  (let [result (popup-actions/handle-action initial-state uf-data [:popup/ax.load-auto-reconnect-setting])]
    (-> (expect (first (first (:uf/fxs result))))
        (.toBe :popup/fx.load-auto-reconnect-setting))))

(defn- test-toggle-auto-reconnect-repl-toggles-true-to-false []
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

(defn- test-toggle-auto-reconnect-repl-toggles-false-to-true []
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

(defn- test-sync-scripts-shadow-updates-content-without-entering-flag []
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
        (.toBe false))))

(defn- test-sync-scripts-shadow-adds-new-items-with-entering-flag []
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
        (.toBe false))))

(defn- test-sync-scripts-shadow-marks-removed-items-as-leaving []
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
        (.toBe true))))

;; ============================================================
;; System Banner Multi-Message Tests
;; ============================================================

(defn- test-show-system-banner-appends-to-empty-list []
  (let [state {:ui/system-banners []}
        result (popup-actions/handle-action state uf-data
                                            [:popup/ax.show-system-banner "success" "Saved!" {}])
        banners (:ui/system-banners (:uf/db result))]
    ;; Should have one banner
    (-> (expect (count banners))
        (.toBe 1))
    ;; Banner should have type and message
    (-> (expect (:type (first banners)))
        (.toBe "success"))
    (-> (expect (:message (first banners)))
        (.toBe "Saved!"))))

(defn- test-show-system-banner-generates-unique-id []
  (let [state {:ui/system-banners []}
        result (popup-actions/handle-action state uf-data
                                            [:popup/ax.show-system-banner "success" "Saved!" {}])
        banner (first (:ui/system-banners (:uf/db result)))]
    ;; Should have an ID
    (-> (expect (:id banner))
        (.toBeTruthy))))

(defn- test-show-system-banner-appends-to-existing-list []
  (let [existing-banner {:id "msg-1" :type "info" :message "Processing..."}
        state {:ui/system-banners [existing-banner]}
        result (popup-actions/handle-action state uf-data
                                            [:popup/ax.show-system-banner "success" "Done!" {}])
        banners (:ui/system-banners (:uf/db result))]
    ;; Should have two banners
    (-> (expect (count banners))
        (.toBe 2))
    ;; Original should be first
    (-> (expect (:message (first banners)))
        (.toBe "Processing..."))
    ;; New should be second
    (-> (expect (:message (second banners)))
        (.toBe "Done!"))))

(defn- test-show-system-banner-schedules-clear-for-specific-banner []
  (let [state {:ui/system-banners []}
        result (popup-actions/handle-action state uf-data
                                            [:popup/ax.show-system-banner "success" "Saved!" {}])
        banner-id (:id (first (:ui/system-banners (:uf/db result))))
        defer-fx (some #(when (= :uf/fx.defer-dispatch (first %)) %) (:uf/fxs result))]
    ;; Should have defer dispatch effect
    (-> (expect defer-fx)
        (.toBeTruthy))
    ;; The deferred action should be clear with the specific ID
    (let [[_fx-name actions-list _delay] defer-fx
          [action-name action-id] (first actions-list)]
      (-> (expect action-name)
          (.toBe :popup/ax.clear-system-banner))
      (-> (expect action-id)
          (.toBe banner-id)))))

(defn- test-clear-system-banner-marks-specific-banner-as-leaving []
  (let [banner {:id "msg-1" :type "success" :message "Saved!"}
        state {:ui/system-banners [banner]}
        result (popup-actions/handle-action state uf-data
                                            [:popup/ax.clear-system-banner "msg-1"])
        banners (:ui/system-banners (:uf/db result))
        updated-banner (first banners)]
    ;; Banner should be marked as leaving
    (-> (expect (:leaving updated-banner))
        (.toBe true))))

(defn- test-clear-system-banner-removes-banner-after-animation []
  (let [banner {:id "msg-1" :type "success" :message "Saved!" :leaving true}
        state {:ui/system-banners [banner]}
        result (popup-actions/handle-action state uf-data
                                            [:popup/ax.clear-system-banner "msg-1"])
        banners (:ui/system-banners (:uf/db result))]
    ;; Banner should be removed
    (-> (expect (count banners))
        (.toBe 0))))

(defn- test-clear-system-banner-only-affects-target-banner []
  (let [banner1 {:id "msg-1" :type "info" :message "A"}
        banner2 {:id "msg-2" :type "success" :message "B"}
        state {:ui/system-banners [banner1 banner2]}
        result (popup-actions/handle-action state uf-data
                                            [:popup/ax.clear-system-banner "msg-1"])
        banners (:ui/system-banners (:uf/db result))]
    ;; Should still have both banners
    (-> (expect (count banners))
        (.toBe 2))
    ;; First should be leaving
    (-> (expect (:leaving (first banners)))
        (.toBe true))
    ;; Second should be unchanged
    (-> (expect (:leaving (second banners)))
        (.toBeFalsy))))

(defn- test-clear-system-banner-schedules-removal-after-animation []
  (let [banner {:id "msg-1" :type "success" :message "Saved!"}
        state {:ui/system-banners [banner]}
        result (popup-actions/handle-action state uf-data
                                            [:popup/ax.clear-system-banner "msg-1"])
        defer-fx (some #(when (= :uf/fx.defer-dispatch (first %)) %) (:uf/fxs result))]
    ;; Should have defer dispatch for removal after 250ms animation
    (-> (expect defer-fx)
        (.toBeTruthy))
    (let [[_fx-name _actions delay-ms] defer-fx]
      (-> (expect delay-ms)
          (.toBe 250)))))

(defn- test-show-system-banner-with-category-replaces-existing []
  (let [existing-banner {:id "msg-1" :type "info" :message "Connecting..." :category "connection"}
        state {:ui/system-banners [existing-banner]}
        result (popup-actions/handle-action state uf-data
                                            [:popup/ax.show-system-banner "success" "Connected!" {} "connection"])
        banners (:ui/system-banners (:uf/db result))]
    ;; Should still have one banner (replaced, not appended)
    (-> (expect (count banners))
        (.toBe 1))
    ;; New banner should have new message
    (-> (expect (:message (first banners)))
        (.toBe "Connected!"))
    ;; Old banner should be marked as leaving
    (-> (expect (:leaving existing-banner))
        (.toBeFalsy))))

(defn- test-show-system-banner-with-category-does-not-replace-different []
  (let [existing-banner {:id "msg-1" :type "info" :message "Loading..." :category "loading"}
        state {:ui/system-banners [existing-banner]}
        result (popup-actions/handle-action state uf-data
                                            [:popup/ax.show-system-banner "success" "Connected!" {} "connection"])
        banners (:ui/system-banners (:uf/db result))]
    ;; Should have two banners (different categories)
    (-> (expect (count banners))
        (.toBe 2))))

(defn- test-show-system-banner-without-category-appends-normally []
  (let [existing-banner {:id "msg-1" :type "info" :message "Connecting..." :category "connection"}
        state {:ui/system-banners [existing-banner]}
        result (popup-actions/handle-action state uf-data
                                            [:popup/ax.show-system-banner "success" "Saved!" {}])
        banners (:ui/system-banners (:uf/db result))]
    ;; Should have two banners (no category match to replace)
    (-> (expect (count banners))
        (.toBe 2))))

;; ============================================================
;; Test Registration
;; ============================================================

(describe "popup actions"
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

            ;; Script actions
            (test ":popup/ax.toggle-script passes data to effect" test-toggle-script-passes-data-to-effect)
            (test ":popup/ax.delete-script removes and triggers effect" test-delete-script-removes-and-triggers-effect)

            ;; Inspect action
            (test ":popup/ax.inspect-script triggers effect and schedules banner" test-inspect-script-triggers-effect-and-schedules-banner)
            (test ":popup/ax.inspect-script passes script to effect" test-inspect-script-passes-script-to-effect)
            (test ":popup/ax.inspect-script returns nil for non-existent" test-inspect-script-returns-nil-for-non-existent)

            ;; Evaluate action
            (test ":popup/ax.evaluate-script triggers effect" test-evaluate-script-triggers-effect)
            (test ":popup/ax.evaluate-script returns nil for non-existent" test-evaluate-script-returns-nil-for-non-existent)

            ;; Settings view actions
            (test ":popup/ax.load-user-origins triggers effect" test-load-user-origins-triggers-effect)
            (test ":popup/ax.set-new-origin updates input" test-set-new-origin-updates-input)
            (test ":popup/ax.add-origin adds valid and clears" test-add-origin-adds-valid-and-clears)
            (test ":popup/ax.add-origin rejects invalid (no trailing slash)" test-add-origin-rejects-invalid-no-trailing-slash)
            (test ":popup/ax.add-origin rejects invalid (wrong protocol)" test-add-origin-rejects-invalid-wrong-protocol)
            (test ":popup/ax.add-origin rejects duplicate in user list" test-add-origin-rejects-duplicate-in-user-list)
            (test ":popup/ax.add-origin rejects duplicate in default list" test-add-origin-rejects-duplicate-in-default-list)
            (test ":popup/ax.remove-origin removes and triggers effect" test-remove-origin-removes-and-triggers-effect)

            ;; Section toggle
            (test ":popup/ax.toggle-section toggles collapsed state from false to true" test-toggle-section-toggles-false-to-true)
            (test ":popup/ax.toggle-section toggles collapsed state from true to false" test-toggle-section-toggles-true-to-false)
            (test ":popup/ax.toggle-section handles nil state (falsy becomes truthy)" test-toggle-section-handles-nil-state)

            ;; Auto-reconnect
            (test ":popup/ax.load-auto-reconnect-setting triggers effect" test-load-auto-reconnect-setting-triggers-effect)
            (test ":popup/ax.toggle-auto-reconnect-repl toggles from true to false" test-toggle-auto-reconnect-repl-toggles-true-to-false)
            (test ":popup/ax.toggle-auto-reconnect-repl toggles from false to true" test-toggle-auto-reconnect-repl-toggles-false-to-true)

            ;; Shadow list sync
            (test ":ui/ax.sync-scripts-shadow updates content without setting entering flag" test-sync-scripts-shadow-updates-content-without-entering-flag)
            (test ":ui/ax.sync-scripts-shadow adds new items with entering flag" test-sync-scripts-shadow-adds-new-items-with-entering-flag)
            (test ":ui/ax.sync-scripts-shadow marks removed items as leaving" test-sync-scripts-shadow-marks-removed-items-as-leaving)

            ;; System banner multi-message
            (test ":popup/ax.show-system-banner appends to empty list" test-show-system-banner-appends-to-empty-list)
            (test ":popup/ax.show-system-banner generates unique ID" test-show-system-banner-generates-unique-id)
            (test ":popup/ax.show-system-banner appends to existing list" test-show-system-banner-appends-to-existing-list)
            (test ":popup/ax.show-system-banner schedules clear for specific banner" test-show-system-banner-schedules-clear-for-specific-banner)
            (test ":popup/ax.clear-system-banner marks specific banner as leaving" test-clear-system-banner-marks-specific-banner-as-leaving)
            (test ":popup/ax.clear-system-banner removes banner after animation" test-clear-system-banner-removes-banner-after-animation)
            (test ":popup/ax.clear-system-banner only affects target banner" test-clear-system-banner-only-affects-target-banner)
            (test ":popup/ax.clear-system-banner schedules removal after animation" test-clear-system-banner-schedules-removal-after-animation)
            (test ":popup/ax.show-system-banner with category replaces existing" test-show-system-banner-with-category-replaces-existing)
            (test ":popup/ax.show-system-banner with category does not replace different" test-show-system-banner-with-category-does-not-replace-different)
            (test ":popup/ax.show-system-banner without category appends normally" test-show-system-banner-without-category-appends-normally)))
