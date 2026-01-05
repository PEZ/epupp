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
