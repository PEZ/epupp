(ns sponsor-actions-test
  (:require ["vitest" :refer [describe test expect]]
            [background-actions.sponsor-actions :as sponsor-actions]))

(defn- test-set-pending-adds-tab-id-with-timestamp []
  (let [state {}
        result (sponsor-actions/set-pending state {:sponsor/tab-id 42 :sponsor/now 1000})]
    (.toEqual (expect (:uf/db result))
              {:sponsor/pending-checks {42 1000}})))

(defn- test-set-pending-preserves-existing-checks []
  (let [state {:sponsor/pending-checks {1 500}}
        result (sponsor-actions/set-pending state {:sponsor/tab-id 42 :sponsor/now 1000})]
    (.toEqual (expect (get-in (:uf/db result) [:sponsor/pending-checks]))
              {1 500, 42 1000})))

(describe "sponsor-actions/set-pending"
          (fn []
            (test "adds tab-id with timestamp to pending-checks" test-set-pending-adds-tab-id-with-timestamp)
            (test "preserves existing pending checks" test-set-pending-preserves-existing-checks)))

(defn- test-consume-pending-true-within-window []
  (let [state {:sponsor/pending-checks {42 1000}}
        result (sponsor-actions/consume-pending
                state {:sponsor/tab-id 42 :sponsor/now 20000
                       :sponsor/tab-url "https://github.com/sponsors/PEZ"
                       :sponsor/send-response identity})]
    (.toEqual (expect (get-in (:uf/db result) [:sponsor/pending-checks])) {})
    (let [[_fx-key params] (first (:uf/fxs result))]
      (.toBe (expect (:pending? params)) true))))

(defn- test-consume-pending-false-outside-window []
  (let [state {:sponsor/pending-checks {42 1000}}
        result (sponsor-actions/consume-pending
                state {:sponsor/tab-id 42 :sponsor/now 50000
                       :sponsor/tab-url "https://github.com/sponsors/PEZ"
                       :sponsor/send-response identity})]
    (let [[_fx-key params] (first (:uf/fxs result))]
      (.toBe (expect (:pending? params)) false))))

(defn- test-consume-pending-false-no-pending-check []
  (let [state {:sponsor/pending-checks {}}
        result (sponsor-actions/consume-pending
                state {:sponsor/tab-id 42 :sponsor/now 1000
                       :sponsor/tab-url nil
                       :sponsor/send-response identity})]
    (let [[_fx-key params] (first (:uf/fxs result))]
      (.toBe (expect (:pending? params)) false))))

(defn- test-consume-pending-nil-tab-id []
  (let [state {:sponsor/pending-checks {}}
        result (sponsor-actions/consume-pending
                state {:sponsor/tab-id nil :sponsor/now 1000
                       :sponsor/tab-url nil
                       :sponsor/send-response identity})]
    (.toEqual (expect (get-in (:uf/db result) [:sponsor/pending-checks])) {})
    (let [[_fx-key params] (first (:uf/fxs result))]
      (.toBe (expect (:pending? params)) false))))

(describe "sponsor-actions/consume-pending"
          (fn []
            (test "returns pending? true when within 30s window" test-consume-pending-true-within-window)
            (test "returns pending? false when outside 30s window" test-consume-pending-false-outside-window)
            (test "returns pending? false when no pending check exists" test-consume-pending-false-no-pending-check)
            (test "handles nil tab-id gracefully" test-consume-pending-nil-tab-id)))
