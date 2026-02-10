(ns fs-actions-test
  "Tests for FS sync action handlers - pure decision logic"
  (:require ["vitest" :refer [describe test expect]]
            [background-actions :as bg-actions]
            [background-actions.fs-actions :as fs-actions]))

;; ============================================================
;; Test Fixtures
;; ============================================================

(def uf-data {:system/now 1737100000000})

(defn- state-with-ws
  "Create state with a WS connection for the given tab-id."
  [tab-id]
  {:ws/connections {tab-id {:ws/socket :dummy :ws/port 1340}}
   :fs/sync-tab-id nil})

(defn- state-without-ws
  "Create state without any WS connections."
  []
  {:ws/connections {}
   :fs/sync-tab-id nil})

;; ============================================================
;; toggle-sync Tests
;; ============================================================

(defn- test-toggle-sync-enables-for-tab-with-ws []
  (let [send-response (fn [])
        state (state-with-ws 42)
        result (fs-actions/toggle-sync state {:fs/tab-id 42 :fs/enabled true :fs/send-response send-response})]
    ;; Should set fs/sync-tab-id to the tab
    (-> (expect (:fs/sync-tab-id (:uf/db result)))
        (.toBe 42))
    ;; Should emit broadcast effect
    (-> (expect (some #(= :fs/fx.broadcast-sync-status! (first %)) (:uf/fxs result)))
        (.toBeTruthy))
    ;; Broadcast should carry the new tab-id
    (-> (expect (some #(and (= :fs/fx.broadcast-sync-status! (first %))
                            (= 42 (second %))) (:uf/fxs result)))
        (.toBeTruthy))
    ;; Should emit success response
    (-> (expect (some #(and (= :msg/fx.send-response (first %))
                            (:success (nth % 2))) (:uf/fxs result)))
        (.toBeTruthy))))

(defn- test-toggle-sync-disables-clears-sync-tab []
  (let [send-response (fn [])
        state (assoc (state-with-ws 42) :fs/sync-tab-id 42)
        result (fs-actions/toggle-sync state {:fs/tab-id 42 :fs/enabled false :fs/send-response send-response})]
    ;; Should clear fs/sync-tab-id
    (-> (expect (:fs/sync-tab-id (:uf/db result)))
        (.toBeNull))
    ;; Broadcast should carry nil
    (-> (expect (some #(and (= :fs/fx.broadcast-sync-status! (first %))
                            (nil? (second %))) (:uf/fxs result)))
        (.toBeTruthy))))

(defn- test-toggle-sync-enables-new-tab-replaces-old []
  (let [send-response (fn [])
        state (-> (state-with-ws 42)
                  (assoc-in [:ws/connections 99] {:ws/socket :dummy :ws/port 1341})
                  (assoc :fs/sync-tab-id 42))
        result (fs-actions/toggle-sync state {:fs/tab-id 99 :fs/enabled true :fs/send-response send-response})]
    ;; Should switch to new tab
    (-> (expect (:fs/sync-tab-id (:uf/db result)))
        (.toBe 99))))

(defn- test-toggle-sync-rejects-enable-without-ws []
  (let [send-response (fn [])
        state (state-without-ws)
        result (fs-actions/toggle-sync state {:fs/tab-id 42 :fs/enabled true :fs/send-response send-response})]
    ;; Should NOT have state update
    (-> (expect (:uf/db result))
        (.toBeUndefined))
    ;; Should emit error response
    (-> (expect (some #(and (= :msg/fx.send-response (first %))
                            (not (:success (nth % 2)))) (:uf/fxs result)))
        (.toBeTruthy))
    ;; Error message should mention REPL connection
    (-> (expect (some #(and (= :msg/fx.send-response (first %))
                            (some-> (nth % 2) :error (.includes "REPL"))) (:uf/fxs result)))
        (.toBeTruthy))))

(describe "fs-actions/toggle-sync"
          (fn []
            (test "enables sync for tab with WS connection" test-toggle-sync-enables-for-tab-with-ws)
            (test "disabling clears sync tab" test-toggle-sync-disables-clears-sync-tab)
            (test "enabling new tab replaces old sync tab" test-toggle-sync-enables-new-tab-replaces-old)
            (test "rejects enable without WS connection" test-toggle-sync-rejects-enable-without-ws)))

;; ============================================================
;; get-sync-status Tests
;; ============================================================

(defn- test-get-sync-status-returns-current-tab-id []
  (let [send-response (fn [])
        state {:fs/sync-tab-id 42}
        result (fs-actions/get-sync-status state {:fs/send-response send-response})]
    ;; Should emit response with current sync tab id
    (-> (expect (some #(and (= :msg/fx.send-response (first %))
                            (= 42 (:fsSyncTabId (nth % 2)))) (:uf/fxs result)))
        (.toBeTruthy))))

(defn- test-get-sync-status-returns-nil-when-no-sync []
  (let [send-response (fn [])
        state {:fs/sync-tab-id nil}
        result (fs-actions/get-sync-status state {:fs/send-response send-response})]
    ;; Should emit response with nil
    (-> (expect (some #(and (= :msg/fx.send-response (first %))
                            (nil? (:fsSyncTabId (nth % 2)))) (:uf/fxs result)))
        (.toBeTruthy))))

(describe "fs-actions/get-sync-status"
          (fn []
            (test "returns current sync tab id" test-get-sync-status-returns-current-tab-id)
            (test "returns nil when no sync tab" test-get-sync-status-returns-nil-when-no-sync)))

;; ============================================================
;; toggle-sync via handle-action Tests
;; ============================================================

(defn- test-action-toggle-sync-enables []
  (let [send-response (fn [])
        state (state-with-ws 42)
        result (bg-actions/handle-action state uf-data
                 [:fs/ax.toggle-sync 42 true send-response])]
    (-> (expect (:fs/sync-tab-id (:uf/db result)))
        (.toBe 42))))

(defn- test-action-get-sync-status []
  (let [send-response (fn [])
        state {:fs/sync-tab-id 99}
        result (bg-actions/handle-action state uf-data
                 [:fs/ax.get-sync-status send-response])]
    (-> (expect (some #(and (= :msg/fx.send-response (first %))
                            (= 99 (:fsSyncTabId (nth % 2)))) (:uf/fxs result)))
        (.toBeTruthy))))

(describe ":fs/ax.toggle-sync via handle-action"
          (fn []
            (test "enables sync for tab" test-action-toggle-sync-enables)))

(describe ":fs/ax.get-sync-status via handle-action"
          (fn []
            (test "returns sync status" test-action-get-sync-status)))

;; ============================================================
;; ws-actions/unregister FS cleanup Tests
;; ============================================================

(defn- test-ws-unregister-clears-fs-sync-when-tab-matches []
  (let [conn {:ws/socket :dummy-ws :ws/port 5555}
        state {:ws/connections {9 conn}
               :fs/sync-tab-id 9}
        result (bg-actions/handle-action state uf-data
                 [:ws/ax.unregister 9])]
    ;; Should clear fs/sync-tab-id
    (-> (expect (:fs/sync-tab-id (:uf/db result)))
        (.toBeNull))
    ;; Should emit ws broadcast
    (-> (expect (some #(= :ws/fx.broadcast-connections-changed! (first %))
                      (:uf/fxs result)))
        (.toBeTruthy))
    ;; Should emit fs sync broadcast with nil
    (-> (expect (some #(and (= :fs/fx.broadcast-sync-status! (first %))
                            (nil? (second %))) (:uf/fxs result)))
        (.toBeTruthy))))

(defn- test-ws-unregister-leaves-fs-sync-when-tab-differs []
  (let [conn {:ws/socket :dummy-ws :ws/port 5555}
        state {:ws/connections {9 conn}
               :fs/sync-tab-id 42}
        result (bg-actions/handle-action state uf-data
                 [:ws/ax.unregister 9])]
    ;; Should NOT clear fs/sync-tab-id (different tab)
    (-> (expect (:fs/sync-tab-id (:uf/db result)))
        (.toBe 42))
    ;; Should emit ws broadcast but NOT fs broadcast
    (-> (expect (some #(= :ws/fx.broadcast-connections-changed! (first %))
                      (:uf/fxs result)))
        (.toBeTruthy))
    (-> (expect (some #(= :fs/fx.broadcast-sync-status! (first %))
                      (:uf/fxs result)))
        (.toBeFalsy))))

(describe ":ws/ax.unregister - FS sync cleanup"
          (fn []
            (test "clears FS sync when disconnecting tab matches" test-ws-unregister-clears-fs-sync-when-tab-matches)
            (test "leaves FS sync unchanged when different tab disconnects" test-ws-unregister-leaves-fs-sync-when-tab-differs)))
