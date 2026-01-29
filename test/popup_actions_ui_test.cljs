(ns popup-actions-ui-test
  "Tests for popup UI-related action handlers"
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
;; Section Toggle Tests
;; ============================================================

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

;; ============================================================
;; Shadow List Sync Tests
;; ============================================================

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
;; Test Suite
;; ============================================================

(describe "Popup UI Actions"
          (fn []
            (describe "Section toggle"
                      (fn []
                        (test "toggles collapsed state from false to true" test-toggle-section-toggles-false-to-true)
                        (test "toggles collapsed state from true to false" test-toggle-section-toggles-true-to-false)
                        (test "handles nil state by defaulting to true" test-toggle-section-handles-nil-state)))

            (describe "Shadow list sync"
                      (fn []
                        (test "updates content without entering flag for unchanged membership" test-sync-scripts-shadow-updates-content-without-entering-flag)
                        (test "adds new items with entering flag" test-sync-scripts-shadow-adds-new-items-with-entering-flag)
                        (test "marks removed items as leaving" test-sync-scripts-shadow-marks-removed-items-as-leaving)))

            (describe "System banners"
                      (fn []
                        (test "appends banner to empty list" test-show-system-banner-appends-to-empty-list)
                        (test "generates unique ID for each banner" test-show-system-banner-generates-unique-id)
                        (test "appends to existing list" test-show-system-banner-appends-to-existing-list)
                        (test "schedules clear for specific banner ID" test-show-system-banner-schedules-clear-for-specific-banner)
                        (test "marks specific banner as leaving on first clear" test-clear-system-banner-marks-specific-banner-as-leaving)
                        (test "removes banner after animation (already leaving)" test-clear-system-banner-removes-banner-after-animation)
                        (test "only affects target banner when clearing" test-clear-system-banner-only-affects-target-banner)
                        (test "schedules removal after 250ms animation delay" test-clear-system-banner-schedules-removal-after-animation)
                        (test "with category replaces existing banner in same category" test-show-system-banner-with-category-replaces-existing)
                        (test "with category does not replace banner in different category" test-show-system-banner-with-category-does-not-replace-different)
                        (test "without category appends normally" test-show-system-banner-without-category-appends-normally)))))

;; ============================================================
;; Modified Scripts Tracking Tests (2.4)
;; ============================================================

(defn- test-mark-scripts-modified-single-script []
  (let [state {:ui/recently-modified-scripts #{}}
        result (popup-actions/handle-action state uf-data
                                            [:popup/ax.mark-scripts-modified ["test.cljs"]])
        new-state (:uf/db result)]
    ;; Script should be added to modified set
    (-> (expect (:ui/recently-modified-scripts new-state))
        (.toContain "test.cljs"))
    ;; Should schedule clear action
    (-> (expect (some #(= :uf/fx.defer-dispatch (first %)) (:uf/fxs result)))
        (.toBeTruthy))))

(defn- test-mark-scripts-modified-multiple-scripts []
  (let [state {:ui/recently-modified-scripts #{}}
        result (popup-actions/handle-action state uf-data
                                            [:popup/ax.mark-scripts-modified ["one.cljs" "two.cljs"]])
        new-state (:uf/db result)]
    ;; Both scripts should be added
    (-> (expect (:ui/recently-modified-scripts new-state))
        (.toContain "one.cljs"))
    (-> (expect (:ui/recently-modified-scripts new-state))
        (.toContain "two.cljs"))))

(defn- test-mark-scripts-modified-appends-to-existing []
  (let [state {:ui/recently-modified-scripts #{"existing.cljs"}}
        result (popup-actions/handle-action state uf-data
                                            [:popup/ax.mark-scripts-modified ["new.cljs"]])
        new-state (:uf/db result)]
    ;; Should have both existing and new
    (-> (expect (:ui/recently-modified-scripts new-state))
        (.toContain "existing.cljs"))
    (-> (expect (:ui/recently-modified-scripts new-state))
        (.toContain "new.cljs"))))

(defn- test-clear-modified-scripts-removes-all []
  (let [state {:ui/recently-modified-scripts #{"one.cljs" "two.cljs"}}
        result (popup-actions/handle-action state uf-data
                                            [:popup/ax.clear-modified-scripts])
        new-state (:uf/db result)]
    ;; Should clear all modified scripts
    (-> (expect (count (:ui/recently-modified-scripts new-state)))
        (.toBe 0))))

;; ============================================================
;; Shadow List Deferred Cleanup Tests (2.5)
;; ============================================================

(defn- test-sync-scripts-shadow-schedules-clear-entering []
  (let [new-script {:script/id "new-1" :script/code "new code"}
        state {:scripts/list [new-script]
               :ui/scripts-shadow []}
        result (popup-actions/handle-action state uf-data
                                            [:ui/ax.sync-scripts-shadow {:added-items [new-script]
                                                                         :removed-ids #{}}])
        defer-fxs (filter #(= :uf/fx.defer-dispatch (first %)) (:uf/fxs result))
        clear-entering-fx (some #(when (= :ui/ax.clear-entering-scripts
                                            (first (first (second %))))
                                   %)
                                defer-fxs)]
    ;; Should schedule clear-entering action
    (-> (expect clear-entering-fx)
        (.toBeTruthy))
    ;; Clear should be scheduled with 50ms delay
    (let [[_fx-name _actions delay] clear-entering-fx]
      (-> (expect delay)
          (.toBe 50)))))

(defn- test-sync-scripts-shadow-schedules-remove-leaving []
  (let [state {:scripts/list []
               :ui/scripts-shadow [{:item {:script/id "to-remove"}
                                    :ui/entering? false
                                    :ui/leaving? false}]}
        result (popup-actions/handle-action state uf-data
                                            [:ui/ax.sync-scripts-shadow {:added-items []
                                                                         :removed-ids #{"to-remove"}}])
        defer-fxs (filter #(= :uf/fx.defer-dispatch (first %)) (:uf/fxs result))
        remove-leaving-fx (some #(when (= :ui/ax.remove-leaving-scripts
                                           (first (first (second %))))
                                    %)
                                defer-fxs)]
    ;; Should schedule remove-leaving action
    (-> (expect remove-leaving-fx)
        (.toBeTruthy))
    ;; Remove should be scheduled with 250ms delay
    (let [[_fx-name _actions delay] remove-leaving-fx]
      (-> (expect delay)
          (.toBe 250)))))

(defn- test-clear-entering-scripts-removes-flag []
  (let [state {:ui/scripts-shadow [{:item {:script/id "new-1"}
                                    :ui/entering? true
                                    :ui/leaving? false}
                                   {:item {:script/id "old-1"}
                                    :ui/entering? false
                                    :ui/leaving? false}]}
        result (popup-actions/handle-action state uf-data
                                            [:ui/ax.clear-entering-scripts #{"new-1"}])
        shadow (:ui/scripts-shadow (:uf/db result))
        new-item (first shadow)
        old-item (second shadow)]
    ;; New item should have entering flag cleared
    (-> (expect (:ui/entering? new-item))
        (.toBe false))
    ;; Old item should remain unchanged
    (-> (expect (:ui/entering? old-item))
        (.toBe false))))

(defn- test-remove-leaving-scripts-removes-items []
  (let [state {:ui/scripts-shadow [{:item {:script/id "leaving-1"}
                                    :ui/entering? false
                                    :ui/leaving? true}
                                   {:item {:script/id "staying-1"}
                                    :ui/entering? false
                                    :ui/leaving? false}]}
        result (popup-actions/handle-action state uf-data
                                            [:ui/ax.remove-leaving-scripts #{"leaving-1"}])
        shadow (:ui/scripts-shadow (:uf/db result))]
    ;; Should have only one item left
    (-> (expect (count shadow))
        (.toBe 1))
    ;; Staying item should remain
    (-> (expect (get-in shadow [0 :item :script/id]))
        (.toBe "staying-1"))))

(describe "Popup Modified Scripts Tracking"
          (fn []
            (test "single script marked modified" test-mark-scripts-modified-single-script)
            (test "multiple scripts marked in batch" test-mark-scripts-modified-multiple-scripts)
            (test "appends to existing modified set" test-mark-scripts-modified-appends-to-existing)
            (test "clear action removes all" test-clear-modified-scripts-removes-all)))

(describe "Popup Shadow List Deferred Cleanup"
          (fn []
            (test "schedules clear-entering after delay" test-sync-scripts-shadow-schedules-clear-entering)
            (test "schedules remove-leaving after delay" test-sync-scripts-shadow-schedules-remove-leaving)
            (test "clear-entering removes entering flag" test-clear-entering-scripts-removes-flag)
            (test "remove-leaving removes items from shadow" test-remove-leaving-scripts-removes-items)))
