(ns event-handler-test
  (:require ["vitest" :refer [describe test expect]]
            [event-handler :as event-handler]))

;; ============================================================
;; handle-action tests (generic action handler)
;; ============================================================

(defn- test-handles-db-ax-assoc-with-single-key-value-pair []
  (let [state {:foo 1}
        result (event-handler/handle-action state {} [:db/ax.assoc :bar 2])]
    (-> (expect (get (:uf/db result) :foo))
        (.toBe 1))
    (-> (expect (get (:uf/db result) :bar))
        (.toBe 2))))

(defn- test-handles-db-ax-assoc-with-multiple-key-value-pairs []
  (let [state {:existing "value"}
        result (event-handler/handle-action state {} [:db/ax.assoc :a 1 :b 2 :c 3])]
    (-> (expect (get (:uf/db result) :a))
        (.toBe 1))
    (-> (expect (get (:uf/db result) :b))
        (.toBe 2))
    (-> (expect (get (:uf/db result) :c))
        (.toBe 3))
    (-> (expect (get (:uf/db result) :existing))
        (.toBe "value"))))

(defn- test-returns-uf-unhandled-ax-for-unknown-action []
  (let [result (event-handler/handle-action {} {} [:unknown/action])]
    (-> (expect result)
        (.toBe :uf/unhandled-ax))))

;; ============================================================
;; handle-actions tests (action batch processing)
;; ============================================================

(defn- test-processes-empty-actions-list []
  (let [state {:initial "state"}
        result (event-handler/handle-actions
                state {} (constantly {:uf/db state}) [])]
    (-> (expect (get (:uf/db result) :initial))
        (.toBe "state"))
    (-> (expect (count (:uf/fxs result)))
        (.toBe 0))))

(defn- test-processes-single-action []
  (let [state {:count 0}
        handler (fn [s _uf [action & _args]]
                  (case action
                    :inc {:uf/db (update s :count inc)}
                    :uf/unhandled-ax))
        result (event-handler/handle-actions state {} handler [[:inc]])]
    (-> (expect (get (:uf/db result) :count))
        (.toBe 1))))

(defn- test-chains-multiple-actions-each-sees-updated-state []
  (let [state {:count 0}
        handler (fn [s _uf [action & _args]]
                  (case action
                    :inc {:uf/db (update s :count inc)}
                    :uf/unhandled-ax))
        result (event-handler/handle-actions
                state {} handler [[:inc] [:inc] [:inc]])]
    (-> (expect (get (:uf/db result) :count))
        (.toBe 3))))

(defn- test-accumulates-effects-from-multiple-actions []
  (let [state {}
        handler (fn [s _uf [action & args]]
                  (case action
                    :emit {:uf/db s :uf/fxs [[:effect (first args)]]}
                    :uf/unhandled-ax))
        result (event-handler/handle-actions
                state {} handler [[:emit "a"] [:emit "b"]])]
    (-> (expect (count (:uf/fxs result)))
        (.toBe 2))))

(defn- test-filters-nil-actions []
  (let [state {:count 0}
        handler (fn [s _uf [action & _args]]
                  (case action
                    :inc {:uf/db (update s :count inc)}
                    :uf/unhandled-ax))
        result (event-handler/handle-actions
                state {} handler [nil [:inc] nil [:inc] nil])]
    (-> (expect (get (:uf/db result) :count))
        (.toBe 2))))

(defn- test-falls-back-to-generic-handler-for-unhandled-actions []
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
        (.toBe 2))))

(defn- test-last-uf-dxs-wins-in-batch []
  (let [state {}
        handler (fn [s _uf [action & args]]
                  (case action
                    :set-dxs {:uf/db s :uf/dxs (first args)}
                    :uf/unhandled-ax))
        result (event-handler/handle-actions
                state {} handler [[:set-dxs [[:first]]] [:set-dxs [[:second]]]])]
    (-> (expect (first (first (:uf/dxs result))))
        (.toBe :second))))

;; ============================================================
;; uf-data context tests
;; ============================================================

(defn- test-passes-uf-data-to-handler []
  (let [state {}
        captured-uf-data (atom nil)
        handler (fn [s uf-data [_action & _args]]
                  (reset! captured-uf-data uf-data)
                  {:uf/db s})
        uf-data {:system/now 1234567890}]
    (event-handler/handle-actions state uf-data handler [[:any-action]])
    (-> (expect (get @captured-uf-data :system/now))
        (.toBe 1234567890))))

;; ============================================================
;; await-fx? tests
;; ============================================================

(defn- test-await-fx-returns-true-for-effects-with-uf-await-sentinel []
  (-> (expect (event-handler/await-fx? [:uf/await :fx.something 1 2]))
      (.toBe true)))

(defn- test-await-fx-returns-false-for-regular-effects []
  (-> (expect (event-handler/await-fx? [:fx.something 1 2]))
      (.toBe false)))

(defn- test-await-fx-returns-false-for-non-vector-inputs []
  (-> (expect (event-handler/await-fx? nil))
      (.toBe false))
  (-> (expect (event-handler/await-fx? "string"))
      (.toBe false))
  (-> (expect (event-handler/await-fx? {:map true}))
      (.toBe false)))

;; ============================================================
;; unwrap-fx tests
;; ============================================================

(defn- test-unwrap-fx-removes-uf-await-sentinel-from-await-effects []
  (let [result (event-handler/unwrap-fx [:uf/await :fx.something 1 2])]
    (-> (expect (first result))
        (.toBe :fx.something))
    (-> (expect (count result))
        (.toBe 3))))

(defn- test-unwrap-fx-returns-regular-effects-unchanged []
  (let [fx [:fx.something 1 2]
        result (event-handler/unwrap-fx fx)]
    (-> (expect (first result))
        (.toBe :fx.something))
    (-> (expect (count result))
        (.toBe 3))))

;; ============================================================
;; replace-prev-result tests
;; ============================================================

(defn- test-replace-prev-result-substitutes-uf-prev-result-with-provided-value []
  (let [fx [:fx.use-result :uf/prev-result :other-arg]
        result (event-handler/replace-prev-result fx {:data "from-previous"})]
    (-> (expect (first result))
        (.toBe :fx.use-result))
    (-> (expect (get (second result) :data))
        (.toBe "from-previous"))
    (-> (expect (nth result 2))
        (.toBe :other-arg))))

(defn- test-replace-prev-result-leaves-effects-without-uf-prev-result-unchanged []
  (let [fx [:fx.normal :arg1 :arg2]
        result (event-handler/replace-prev-result fx {:ignored "data"})]
    (-> (expect (first result))
        (.toBe :fx.normal))
    (-> (expect (second result))
        (.toBe :arg1))
    (-> (expect (nth result 2))
        (.toBe :arg2))))

(defn- test-replace-prev-result-handles-multiple-uf-prev-result-occurrences []
  (let [fx [:fx.multi :uf/prev-result :other :uf/prev-result]
        result (event-handler/replace-prev-result fx "replaced")]
    (-> (expect (second result))
        (.toBe "replaced"))
    (-> (expect (nth result 3))
        (.toBe "replaced"))))

;; ============================================================
;; get-list-watcher-actions tests (list change detection)
;; ============================================================

(defn- test-get-list-watcher-actions-returns-empty-when-no-watchers-declared []
  (let [old-state {:items [1 2 3]}
        new-state {:items [1 2]}
        result (event-handler/get-list-watcher-actions old-state new-state)]
    (-> (expect (count result))
        (.toBe 0))))

(defn- test-get-list-watcher-actions-returns-empty-when-watched-list-unchanged []
  (let [old-state {:uf/list-watchers {:items {:id-fn identity :on-change :ax.changed}}
                   :items [1 2 3]}
        new-state {:uf/list-watchers {:items {:id-fn identity :on-change :ax.changed}}
                   :items [1 2 3]}
        result (event-handler/get-list-watcher-actions old-state new-state)]
    (-> (expect (count result))
        (.toBe 0))))

(defn- test-get-list-watcher-actions-detects-added-items []
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
        (.toBe 0))))

(defn- test-get-list-watcher-actions-detects-removed-items []
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
        (.toBe true))))

(defn- test-get-list-watcher-actions-detects-both-added-and-removed []
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
        (.toBe true))))

(defn- test-get-list-watcher-actions-uses-id-fn-for-complex-items []
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
        (.toBe true))))

(defn- test-get-list-watcher-actions-handles-multiple-watchers-independently []
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
        (.toBe 2))))

(defn- test-get-list-watcher-actions-treats-nil-lists-as-empty []
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
        (.toBe true))))

;; ============================================================
;; dispatch! list-watchers integration tests
;; ============================================================

(defn- test-dispatch-triggers-watcher-actions-when-list-items-are-added []
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
        (.toBe true))))

(defn- test-dispatch-triggers-watcher-actions-when-list-items-are-removed []
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
        (.toBe true))))

(defn- test-dispatch-watcher-action-can-schedule-effects []
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
        (.toBe :animate))))

;; ============================================================
;; Shadow list watcher tests
;; ============================================================

(defn- test-shadow-list-watcher-detects-items-in-source-but-not-in-shadow []
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
        (.toBe 0))))

(defn- test-shadow-list-watcher-detects-items-in-shadow-but-not-in-source []
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
        (.toBe true))))

(defn- test-shadow-list-watcher-returns-empty-when-shadow-matches-source []
  (let [state {:uf/list-watchers {:scripts/list {:id-fn :script/id
                                                 :shadow-path :ui/scripts-shadow
                                                 :on-change :ax.sync}}
               :scripts/list [{:script/id "a"} {:script/id "b"}]
               :ui/scripts-shadow [{:item {:script/id "a"} :ui/entering? false :ui/leaving? false}
                                   {:item {:script/id "b"} :ui/entering? false :ui/leaving? false}]}
        result (event-handler/get-list-watcher-actions state state)]
    (-> (expect (count result))
        (.toBe 0))))

(defn- test-shadow-list-watcher-ignores-items-already-marked-as-leaving []
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
        (.toBe 0))))

(defn- test-shadow-list-watcher-treats-nil-shadow-as-empty []
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
        (.toBe 2))))

;; ============================================================
;; Content change detection tests
;; ============================================================

(defn- test-content-change-detection-detects-content-changes-for-items-with-same-id []
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
          (.toBe 0)))))

(defn- test-content-change-detection-does-not-fire-when-content-is-identical []
  (let [state {:uf/list-watchers {:scripts/list {:id-fn :script/id
                                                 :shadow-path :ui/scripts-shadow
                                                 :on-change :ax.sync}}
               :scripts/list [{:script/id "a" :script/code "same"} {:script/id "b"}]
               :ui/scripts-shadow [{:item {:script/id "a" :script/code "same"} :ui/entering? false :ui/leaving? false}
                                   {:item {:script/id "b"} :ui/entering? false :ui/leaving? false}]}
        result (event-handler/get-list-watcher-actions state state)]
    (-> (expect (count result))
        (.toBe 0))))

;; ============================================================
;; Test Registration
;; ============================================================

(describe "handle-action"
          (fn []
            (test "handles :db/ax.assoc with single key-value pair" test-handles-db-ax-assoc-with-single-key-value-pair)
            (test "handles :db/ax.assoc with multiple key-value pairs" test-handles-db-ax-assoc-with-multiple-key-value-pairs)
            (test "returns :uf/unhandled-ax for unknown action" test-returns-uf-unhandled-ax-for-unknown-action)))

(describe "handle-actions"
          (fn []
            (test "processes empty actions list" test-processes-empty-actions-list)
            (test "processes single action" test-processes-single-action)
            (test "chains multiple actions - each sees updated state" test-chains-multiple-actions-each-sees-updated-state)
            (test "accumulates effects from multiple actions" test-accumulates-effects-from-multiple-actions)
            (test "filters nil actions" test-filters-nil-actions)
            (test "falls back to generic handler for unhandled actions" test-falls-back-to-generic-handler-for-unhandled-actions)
            (test "last :uf/dxs wins in batch" test-last-uf-dxs-wins-in-batch)))

(describe "uf-data context"
          (fn []
            (test "passes uf-data to handler" test-passes-uf-data-to-handler)))

(describe "await-fx?"
          (fn []
            (test "returns true for effects with :uf/await sentinel" test-await-fx-returns-true-for-effects-with-uf-await-sentinel)
            (test "returns false for regular effects" test-await-fx-returns-false-for-regular-effects)
            (test "returns false for non-vector inputs" test-await-fx-returns-false-for-non-vector-inputs)))

(describe "unwrap-fx"
          (fn []
            (test "removes :uf/await sentinel from await effects" test-unwrap-fx-removes-uf-await-sentinel-from-await-effects)
            (test "returns regular effects unchanged" test-unwrap-fx-returns-regular-effects-unchanged)))

(describe "replace-prev-result"
          (fn []
            (test "substitutes :uf/prev-result with provided value" test-replace-prev-result-substitutes-uf-prev-result-with-provided-value)
            (test "leaves effects without :uf/prev-result unchanged" test-replace-prev-result-leaves-effects-without-uf-prev-result-unchanged)
            (test "handles multiple :uf/prev-result occurrences" test-replace-prev-result-handles-multiple-uf-prev-result-occurrences)))

(describe "get-list-watcher-actions"
          (fn []
            (test "returns empty when no watchers declared" test-get-list-watcher-actions-returns-empty-when-no-watchers-declared)
            (test "returns empty when watched list unchanged" test-get-list-watcher-actions-returns-empty-when-watched-list-unchanged)
            (test "detects added items" test-get-list-watcher-actions-detects-added-items)
            (test "detects removed items" test-get-list-watcher-actions-detects-removed-items)
            (test "detects both added and removed" test-get-list-watcher-actions-detects-both-added-and-removed)
            (test "uses id-fn for complex items" test-get-list-watcher-actions-uses-id-fn-for-complex-items)
            (test "handles multiple watchers independently" test-get-list-watcher-actions-handles-multiple-watchers-independently)
            (test "treats nil lists as empty" test-get-list-watcher-actions-treats-nil-lists-as-empty)))

(describe "dispatch! with list-watchers"
          (fn []
            (test "triggers watcher actions when list items are added" test-dispatch-triggers-watcher-actions-when-list-items-are-added)
            (test "triggers watcher actions when list items are removed" test-dispatch-triggers-watcher-actions-when-list-items-are-removed)
            (test "watcher action can schedule effects" test-dispatch-watcher-action-can-schedule-effects)))

(describe "get-list-watcher-actions with shadow-path"
          (fn []
            (test "detects items in source but not in shadow (additions)" test-shadow-list-watcher-detects-items-in-source-but-not-in-shadow)
            (test "detects items in shadow but not in source (removals)" test-shadow-list-watcher-detects-items-in-shadow-but-not-in-source)
            (test "returns empty when shadow matches source" test-shadow-list-watcher-returns-empty-when-shadow-matches-source)
            (test "ignores items already marked as leaving in shadow" test-shadow-list-watcher-ignores-items-already-marked-as-leaving)
            (test "treats nil shadow as empty (all items are additions)" test-shadow-list-watcher-treats-nil-shadow-as-empty)))

(describe "get-list-watcher-actions content change detection"
          (fn []
            (test "detects content changes for items with same ID" test-content-change-detection-detects-content-changes-for-items-with-same-id)
            (test "does not fire when content is identical" test-content-change-detection-does-not-fire-when-content-is-identical)))
