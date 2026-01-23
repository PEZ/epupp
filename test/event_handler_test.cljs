(ns event-handler-test
  (:require ["vitest" :refer [describe test expect]]
            [event-handler :as event-handler]))

;; ============================================================
;; handle-action tests (generic action handler)
;; ============================================================

(describe "handle-action"
          (fn []
            (test "handles :db/ax.assoc with single key-value pair"
                  (fn []
                    (let [state {:foo 1}
                          result (event-handler/handle-action state {} [:db/ax.assoc :bar 2])]
                      (-> (expect (get (:uf/db result) :foo))
                          (.toBe 1))
                      (-> (expect (get (:uf/db result) :bar))
                          (.toBe 2)))))

            (test "handles :db/ax.assoc with multiple key-value pairs"
                  (fn []
                    (let [state {:existing "value"}
                          result (event-handler/handle-action state {} [:db/ax.assoc :a 1 :b 2 :c 3])]
                      (-> (expect (get (:uf/db result) :a))
                          (.toBe 1))
                      (-> (expect (get (:uf/db result) :b))
                          (.toBe 2))
                      (-> (expect (get (:uf/db result) :c))
                          (.toBe 3))
                      (-> (expect (get (:uf/db result) :existing))
                          (.toBe "value")))))

            (test "returns :uf/unhandled-ax for unknown action"
                  (fn []
                    (let [result (event-handler/handle-action {} {} [:unknown/action])]
                      (-> (expect result)
                          (.toBe :uf/unhandled-ax)))))))

;; ============================================================
;; handle-actions tests (action batch processing)
;; ============================================================

(describe "handle-actions"
          (fn []
            (test "processes empty actions list"
                  (fn []
                    (let [state {:initial "state"}
                          result (event-handler/handle-actions
                                  state {} (constantly {:uf/db state}) [])]
                      (-> (expect (get (:uf/db result) :initial))
                          (.toBe "state"))
                      (-> (expect (count (:uf/fxs result)))
                          (.toBe 0)))))

            (test "processes single action"
                  (fn []
                    (let [state {:count 0}
                          handler (fn [s _uf [action & _args]]
                                    (case action
                                      :inc {:uf/db (update s :count inc)}
                                      :uf/unhandled-ax))
                          result (event-handler/handle-actions state {} handler [[:inc]])]
                      (-> (expect (get (:uf/db result) :count))
                          (.toBe 1)))))

            (test "chains multiple actions - each sees updated state"
                  (fn []
                    (let [state {:count 0}
                          handler (fn [s _uf [action & _args]]
                                    (case action
                                      :inc {:uf/db (update s :count inc)}
                                      :uf/unhandled-ax))
                          result (event-handler/handle-actions
                                  state {} handler [[:inc] [:inc] [:inc]])]
                      (-> (expect (get (:uf/db result) :count))
                          (.toBe 3)))))

            (test "accumulates effects from multiple actions"
                  (fn []
                    (let [state {}
                          handler (fn [s _uf [action & args]]
                                    (case action
                                      :emit {:uf/db s :uf/fxs [[:effect (first args)]]}
                                      :uf/unhandled-ax))
                          result (event-handler/handle-actions
                                  state {} handler [[:emit "a"] [:emit "b"]])]
                      (-> (expect (count (:uf/fxs result)))
                          (.toBe 2)))))

            (test "filters nil actions"
                  (fn []
                    (let [state {:count 0}
                          handler (fn [s _uf [action & _args]]
                                    (case action
                                      :inc {:uf/db (update s :count inc)}
                                      :uf/unhandled-ax))
                          result (event-handler/handle-actions
                                  state {} handler [nil [:inc] nil [:inc] nil])]
                      (-> (expect (get (:uf/db result) :count))
                          (.toBe 2)))))

            (test "falls back to generic handler for unhandled actions"
                  (fn []
                    (let [state {:foo 1}
              ;; Custom handler doesn't know :db/ax.assoc
                          custom-handler (fn [_s _uf [action & _args]]
                                           (case action
                                             :custom {:uf/db {:custom true}}
                                             :uf/unhandled-ax))
                          result (event-handler/handle-actions
                                  state {} custom-handler [[:db/ax.assoc :bar 2]])]
          ;; Should fall back to generic handler
                      (-> (expect (get (:uf/db result) :bar))
                          (.toBe 2)))))

            (test "last :uf/dxs wins in batch"
                  (fn []
                    (let [state {}
                          handler (fn [s _uf [action & args]]
                                    (case action
                                      :set-dxs {:uf/db s :uf/dxs (first args)}
                                      :uf/unhandled-ax))
                          result (event-handler/handle-actions
                                  state {} handler [[:set-dxs [[:first]]] [:set-dxs [[:second]]]])]
                      (-> (expect (first (first (:uf/dxs result))))
                          (.toBe :second)))))))

;; ============================================================
;; uf-data context tests
;; ============================================================

(describe "uf-data context"
          (fn []
            (test "passes uf-data to handler"
                  (fn []
                    (let [state {}
                          captured-uf-data (atom nil)
                          handler (fn [s uf-data [_action & _args]]
                                    (reset! captured-uf-data uf-data)
                                    {:uf/db s})
                          uf-data {:system/now 1234567890}]
                      (event-handler/handle-actions state uf-data handler [[:any-action]])
                      (-> (expect (get @captured-uf-data :system/now))
                          (.toBe 1234567890)))))))

;; ============================================================
;; await-fx? tests
;; ============================================================

(describe "await-fx?"
          (fn []
            (test "returns true for effects with :uf/await sentinel"
                  (fn []
                    (-> (expect (event-handler/await-fx? [:uf/await :fx.something 1 2]))
                        (.toBe true))))

            (test "returns false for regular effects"
                  (fn []
                    (-> (expect (event-handler/await-fx? [:fx.something 1 2]))
                        (.toBe false))))

            (test "returns false for non-vector inputs"
                  (fn []
                    (-> (expect (event-handler/await-fx? nil))
                        (.toBe false))
                    (-> (expect (event-handler/await-fx? "string"))
                        (.toBe false))
                    (-> (expect (event-handler/await-fx? {:map true}))
                        (.toBe false))))))

;; ============================================================
;; unwrap-fx tests
;; ============================================================

(describe "unwrap-fx"
          (fn []
            (test "removes :uf/await sentinel from await effects"
                  (fn []
                    (let [result (event-handler/unwrap-fx [:uf/await :fx.something 1 2])]
                      (-> (expect (first result))
                          (.toBe :fx.something))
                      (-> (expect (count result))
                          (.toBe 3)))))

            (test "returns regular effects unchanged"
                  (fn []
                    (let [fx [:fx.something 1 2]
                          result (event-handler/unwrap-fx fx)]
                      (-> (expect (first result))
                          (.toBe :fx.something))
                      (-> (expect (count result))
                          (.toBe 3)))))))

;; ============================================================
;; replace-prev-result tests
;; ============================================================

(describe "replace-prev-result"
          (fn []
            (test "substitutes :uf/prev-result with provided value"
                  (fn []
                    (let [fx [:fx.use-result :uf/prev-result :other-arg]
                          result (event-handler/replace-prev-result fx {:data "from-previous"})]
                      (-> (expect (first result))
                          (.toBe :fx.use-result))
                      (-> (expect (get (second result) :data))
                          (.toBe "from-previous"))
                      (-> (expect (nth result 2))
                          (.toBe :other-arg)))))

            (test "leaves effects without :uf/prev-result unchanged"
                  (fn []
                    (let [fx [:fx.normal :arg1 :arg2]
                          result (event-handler/replace-prev-result fx {:ignored "data"})]
                      (-> (expect (first result))
                          (.toBe :fx.normal))
                      (-> (expect (second result))
                          (.toBe :arg1))
                      (-> (expect (nth result 2))
                          (.toBe :arg2)))))

            (test "handles multiple :uf/prev-result occurrences"
                  (fn []
                    (let [fx [:fx.multi :uf/prev-result :other :uf/prev-result]
                          result (event-handler/replace-prev-result fx "replaced")]
                      (-> (expect (second result))
                          (.toBe "replaced"))
                      (-> (expect (nth result 3))
                          (.toBe "replaced")))))))

;; ============================================================
;; get-list-watcher-actions tests (list change detection)
;; ============================================================

(describe "get-list-watcher-actions"
          (fn []
            (test "returns empty when no watchers declared"
                  (fn []
                    (let [old-state {:items [1 2 3]}
                          new-state {:items [1 2]}
                          result (event-handler/get-list-watcher-actions old-state new-state)]
                      (-> (expect (count result))
                          (.toBe 0)))))

            (test "returns empty when watched list unchanged"
                  (fn []
                    (let [old-state {:uf/list-watchers {:items {:id-fn identity :on-change :ax.changed}}
                                     :items [1 2 3]}
                          new-state {:uf/list-watchers {:items {:id-fn identity :on-change :ax.changed}}
                                     :items [1 2 3]}
                          result (event-handler/get-list-watcher-actions old-state new-state)]
                      (-> (expect (count result))
                          (.toBe 0)))))

            (test "detects added items"
                  (fn []
                    (let [old-state {:uf/list-watchers {:items {:id-fn identity :on-change :ax.changed}}
                                     :items [1 2]}
                          new-state {:uf/list-watchers {:items {:id-fn identity :on-change :ax.changed}}
                                     :items [1 2 3]}
                          result (event-handler/get-list-watcher-actions old-state new-state)
                          [action-key payload] (first result)]
                      (-> (expect (count result))
                          (.toBe 1))
                      (-> (expect action-key)
                          (.toBe :ax.changed))
                      (-> (expect (contains? (:added payload) 3))
                          (.toBe true))
                      (-> (expect (count (:removed payload)))
                          (.toBe 0)))))

            (test "detects removed items"
                  (fn []
                    (let [old-state {:uf/list-watchers {:items {:id-fn identity :on-change :ax.changed}}
                                     :items [1 2 3]}
                          new-state {:uf/list-watchers {:items {:id-fn identity :on-change :ax.changed}}
                                     :items [1 2]}
                          result (event-handler/get-list-watcher-actions old-state new-state)
                          [action-key payload] (first result)]
                      (-> (expect (count result))
                          (.toBe 1))
                      (-> (expect action-key)
                          (.toBe :ax.changed))
                      (-> (expect (count (:added payload)))
                          (.toBe 0))
                      (-> (expect (contains? (:removed payload) 3))
                          (.toBe true)))))

            (test "detects both added and removed"
                  (fn []
                    (let [old-state {:uf/list-watchers {:items {:id-fn identity :on-change :ax.changed}}
                                     :items [1 2 3]}
                          new-state {:uf/list-watchers {:items {:id-fn identity :on-change :ax.changed}}
                                     :items [2 3 4]}
                          result (event-handler/get-list-watcher-actions old-state new-state)
                          [action-key payload] (first result)]
                      (-> (expect (count result))
                          (.toBe 1))
                      (-> (expect action-key)
                          (.toBe :ax.changed))
                      (-> (expect (contains? (:added payload) 4))
                          (.toBe true))
                      (-> (expect (contains? (:removed payload) 1))
                          (.toBe true)))))

            (test "uses id-fn for complex items"
                  (fn []
                    (let [old-state {:uf/list-watchers {:scripts {:id-fn :script/id :on-change :ax.scripts-changed}}
                                     :scripts [{:script/id "a" :name "Script A"}
                                               {:script/id "b" :name "Script B"}]}
                          new-state {:uf/list-watchers {:scripts {:id-fn :script/id :on-change :ax.scripts-changed}}
                                     :scripts [{:script/id "b" :name "Script B"}
                                               {:script/id "c" :name "Script C"}]}
                          result (event-handler/get-list-watcher-actions old-state new-state)
                          [action-key payload] (first result)]
                      (-> (expect (count result))
                          (.toBe 1))
                      (-> (expect action-key)
                          (.toBe :ax.scripts-changed))
                      (-> (expect (contains? (:added payload) "c"))
                          (.toBe true))
                      (-> (expect (contains? (:removed payload) "a"))
                          (.toBe true)))))

            (test "handles multiple watchers independently"
                  (fn []
                    (let [old-state {:uf/list-watchers {:items {:id-fn identity :on-change :ax.items-changed}
                                                        :tags {:id-fn identity :on-change :ax.tags-changed}}
                                     :items [1 2]
                                     :tags ["a" "b"]}
                          new-state {:uf/list-watchers {:items {:id-fn identity :on-change :ax.items-changed}
                                                        :tags {:id-fn identity :on-change :ax.tags-changed}}
                                     :items [1 2 3]
                                     :tags ["b" "c"]}
                          result (event-handler/get-list-watcher-actions old-state new-state)]
                      (-> (expect (count result))
                          (.toBe 2)))))

            (test "treats nil lists as empty"
                  (fn []
                    (let [old-state {:uf/list-watchers {:items {:id-fn identity :on-change :ax.changed}}
                                     :items nil}
                          new-state {:uf/list-watchers {:items {:id-fn identity :on-change :ax.changed}}
                                     :items [1 2]}
                          result (event-handler/get-list-watcher-actions old-state new-state)
                          [action-key payload] (first result)]
                      (-> (expect (count result))
                          (.toBe 1))
                      (-> (expect action-key)
                          (.toBe :ax.changed))
                      (-> (expect (contains? (:added payload) 1))
                          (.toBe true))
                      (-> (expect (contains? (:added payload) 2))
                          (.toBe true)))))))

;; ============================================================
;; dispatch! list-watchers integration tests
;; ============================================================

(describe "dispatch! with list-watchers"
          (fn []
            (test "triggers watcher actions when list items are added"
                  (fn []
                    (let [!state (atom {:uf/list-watchers {:items {:id-fn identity :on-change :ax.items-changed}}
                                        :items [1 2]
                                        :change-log []})
                          ax-handler (fn [state _uf [action & args]]
                                       (case action
                                         :ax.add-item
                                         {:uf/db (update state :items conj (first args))}
                                         :ax.items-changed
                                         {:uf/db (update state :change-log conj (first args))}
                                         :uf/unhandled-ax))
                          ex-handler (fn [_dispatch _fx] :uf/unhandled-fx)]
                      (event-handler/dispatch! !state ax-handler ex-handler [[:ax.add-item 3]])
                      (-> (expect (contains? (set (:items @!state)) 3))
                          (.toBe true))
                      ;; Watcher should have been triggered
                      (-> (expect (count (:change-log @!state)))
                          (.toBe 1))
                      (-> (expect (contains? (:added (first (:change-log @!state))) 3))
                          (.toBe true)))))

            (test "triggers watcher actions when list items are removed"
                  (fn []
                    (let [!state (atom {:uf/list-watchers {:items {:id-fn identity :on-change :ax.items-changed}}
                                        :items [1 2 3]
                                        :removed-ids #{}})
                          ax-handler (fn [state _uf [action & args]]
                                       (case action
                                         :ax.remove-item
                                         {:uf/db (update state :items (fn [items]
                                                                        (vec (remove #(= % (first args)) items))))}
                                         :ax.items-changed
                                         {:uf/db (update state :removed-ids into (:removed (first args)))}
                                         :uf/unhandled-ax))
                          ex-handler (fn [_dispatch _fx] :uf/unhandled-fx)]
                      (event-handler/dispatch! !state ax-handler ex-handler [[:ax.remove-item 2]])
                      ;; Item removed
                      (-> (expect (contains? (set (:items @!state)) 2))
                          (.toBe false))
                      ;; Watcher triggered with removed ID
                      (-> (expect (contains? (:removed-ids @!state) 2))
                          (.toBe true)))))

            (test "watcher action can schedule effects"
                  (fn []
                    (let [!state (atom {:uf/list-watchers {:items {:id-fn identity :on-change :ax.items-changed}}
                                        :items [1]})
                          effects-log (atom [])
                          ax-handler (fn [state _uf [action & args]]
                                       (case action
                                         :ax.add-item
                                         {:uf/db (update state :items conj (first args))}
                                         :ax.items-changed
                                         {:uf/db state
                                          :uf/fxs [[:fx.animate-entry (first args)]]}
                                         :uf/unhandled-ax))
                          ex-handler (fn [_dispatch [fx & args]]
                                       (case fx
                                         :fx.animate-entry
                                         (swap! effects-log conj [:animate (first args)])
                                         :uf/unhandled-fx))]
                      (event-handler/dispatch! !state ax-handler ex-handler [[:ax.add-item 2]])
                      ;; Effect should have been scheduled from watcher action
                      (-> (expect (count @effects-log))
                          (.toBe 1))
                      (-> (expect (first (first @effects-log)))
                          (.toBe :animate)))))))

;; ============================================================
;; Shadow list watcher tests
;; ============================================================

(describe "get-list-watcher-actions with shadow-path"
          (fn []
            (test "detects items in source but not in shadow (additions)"
                  (fn []
                    (let [state {:uf/list-watchers {:scripts/list {:id-fn :script/id
                                                                   :shadow-path :ui/scripts-shadow
                                                                   :on-change :ax.sync}}
                                 :scripts/list [{:script/id "a"} {:script/id "b"} {:script/id "c"}]
                                 :ui/scripts-shadow [{:item {:script/id "a"} :ui/entering? false :ui/leaving? false}
                                                     {:item {:script/id "b"} :ui/entering? false :ui/leaving? false}]}
                          result (event-handler/get-list-watcher-actions state state)
                          [action-key payload] (first result)]
                      (-> (expect (count result))
                          (.toBe 1))
                      (-> (expect action-key)
                          (.toBe :ax.sync))
                      ;; Should have the full item for additions
                      (-> (expect (count (:added-items payload)))
                          (.toBe 1))
                      (-> (expect (:script/id (first (:added-items payload))))
                          (.toBe "c"))
                      ;; No removals
                      (-> (expect (count (:removed-ids payload)))
                          (.toBe 0)))))

            (test "detects items in shadow but not in source (removals)"
                  (fn []
                    (let [state {:uf/list-watchers {:scripts/list {:id-fn :script/id
                                                                   :shadow-path :ui/scripts-shadow
                                                                   :on-change :ax.sync}}
                                 :scripts/list [{:script/id "a"}]
                                 :ui/scripts-shadow [{:item {:script/id "a"} :ui/entering? false :ui/leaving? false}
                                                     {:item {:script/id "b"} :ui/entering? false :ui/leaving? false}]}
                          result (event-handler/get-list-watcher-actions state state)
                          [action-key payload] (first result)]
                      (-> (expect (count result))
                          (.toBe 1))
                      (-> (expect action-key)
                          (.toBe :ax.sync))
                      ;; No additions
                      (-> (expect (count (:added-items payload)))
                          (.toBe 0))
                      ;; Should have ID for removal
                      (-> (expect (contains? (:removed-ids payload) "b"))
                          (.toBe true)))))

            (test "returns empty when shadow matches source"
                  (fn []
                    (let [state {:uf/list-watchers {:scripts/list {:id-fn :script/id
                                                                   :shadow-path :ui/scripts-shadow
                                                                   :on-change :ax.sync}}
                                 :scripts/list [{:script/id "a"} {:script/id "b"}]
                                 :ui/scripts-shadow [{:item {:script/id "a"} :ui/entering? false :ui/leaving? false}
                                                     {:item {:script/id "b"} :ui/entering? false :ui/leaving? false}]}
                          result (event-handler/get-list-watcher-actions state state)]
                      (-> (expect (count result))
                          (.toBe 0)))))

            (test "ignores items already marked as leaving in shadow"
                  (fn []
                    (let [state {:uf/list-watchers {:scripts/list {:id-fn :script/id
                                                                   :shadow-path :ui/scripts-shadow
                                                                   :on-change :ax.sync}}
                                 :scripts/list [{:script/id "a"}]
                                 ;; "b" is already leaving - should not trigger removal again
                                 :ui/scripts-shadow [{:item {:script/id "a"} :ui/entering? false :ui/leaving? false}
                                                     {:item {:script/id "b"} :ui/entering? false :ui/leaving? true}]}
                          result (event-handler/get-list-watcher-actions state state)]
                      ;; No action because "b" is already leaving
                      (-> (expect (count result))
                          (.toBe 0)))))

            (test "treats nil shadow as empty (all items are additions)"
                  (fn []
                    (let [state {:uf/list-watchers {:scripts/list {:id-fn :script/id
                                                                   :shadow-path :ui/scripts-shadow
                                                                   :on-change :ax.sync}}
                                 :scripts/list [{:script/id "a"} {:script/id "b"}]
                                 :ui/scripts-shadow nil}
                          result (event-handler/get-list-watcher-actions state state)
                          [_action-key payload] (first result)]
                      (-> (expect (count result))
                          (.toBe 1))
                      (-> (expect (count (:added-items payload)))
                          (.toBe 2)))))))

(describe "get-list-watcher-actions content change detection"
          (fn []
            (test "detects content changes for items with same ID"
                  (fn []
                    (let [state {:uf/list-watchers {:scripts/list {:id-fn :script/id
                                                                   :shadow-path :ui/scripts-shadow
                                                                   :on-change :ax.sync}}
                                 ;; Source has updated content for "a"
                                 :scripts/list [{:script/id "a" :script/code "updated"}
                                                {:script/id "b" :script/code "original"}]
                                 ;; Shadow has old content for "a"
                                 :ui/scripts-shadow [{:item {:script/id "a" :script/code "original"} :ui/entering? false :ui/leaving? false}
                                                     {:item {:script/id "b" :script/code "original"} :ui/entering? false :ui/leaving? false}]}
                          result (event-handler/get-list-watcher-actions state state)]
                      ;; Should fire because content changed
                      (-> (expect (count result))
                          (.toBe 1))
                      ;; No membership changes
                      (let [[_action-key payload] (first result)]
                        (-> (expect (count (:added-items payload)))
                            (.toBe 0))
                        (-> (expect (count (:removed-ids payload)))
                            (.toBe 0))))))

            (test "does not fire when content is identical"
                  (fn []
                    (let [state {:uf/list-watchers {:scripts/list {:id-fn :script/id
                                                                   :shadow-path :ui/scripts-shadow
                                                                   :on-change :ax.sync}}
                                 :scripts/list [{:script/id "a" :script/code "same"} {:script/id "b"}]
                                 :ui/scripts-shadow [{:item {:script/id "a" :script/code "same"} :ui/entering? false :ui/leaving? false}
                                                     {:item {:script/id "b"} :ui/entering? false :ui/leaving? false}]}
                          result (event-handler/get-list-watcher-actions state state)]
                      (-> (expect (count result))
                          (.toBe 0)))))))
