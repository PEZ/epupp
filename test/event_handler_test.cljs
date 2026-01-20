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
