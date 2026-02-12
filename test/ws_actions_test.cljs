(ns ws-actions-test
  (:require ["vitest" :refer [describe test expect]]
            [background-actions.ws-actions :as ws-actions]))

;; ============================================================
;; handle-connect Tests
;; ============================================================

(defn- test-handle-connect-passes-connections-from-state-to-effect []
  (let [state {:ws/connections {1 {:ws/port 1234}}}
        result (ws-actions/handle-connect state {:ws/tab-id 2 :ws/port 5678})]
    (.toEqual (expect (first (:uf/fxs result)))
              [:ws/fx.handle-connect {1 {:ws/port 1234}} 2 5678])))

(defn- test-handle-connect-defaults-to-empty-connections []
  (let [result (ws-actions/handle-connect {} {:ws/tab-id 1 :ws/port 4444})]
    (.toEqual (expect (first (:uf/fxs result)))
              [:ws/fx.handle-connect {} 1 4444])))

(describe "ws-actions/handle-connect"
          (fn []
            (test "passes connections from state to effect"
                  test-handle-connect-passes-connections-from-state-to-effect)
            (test "defaults to empty connections when nil"
                  test-handle-connect-defaults-to-empty-connections)))

;; ============================================================
;; handle-send Tests
;; ============================================================

(defn- test-handle-send-passes-connections-from-state-to-effect []
  (let [state {:ws/connections {1 {:ws/port 1234}}}
        result (ws-actions/handle-send state {:ws/tab-id 1 :ws/data "some-data"})]
    (.toEqual (expect (first (:uf/fxs result)))
              [:ws/fx.handle-send {1 {:ws/port 1234}} 1 "some-data"])))

(defn- test-handle-send-defaults-to-empty-connections []
  (let [result (ws-actions/handle-send {} {:ws/tab-id 1 :ws/data "data"})]
    (.toEqual (expect (first (:uf/fxs result)))
              [:ws/fx.handle-send {} 1 "data"])))

(describe "ws-actions/handle-send"
          (fn []
            (test "passes connections from state to effect"
                  test-handle-send-passes-connections-from-state-to-effect)
            (test "defaults to empty connections when nil"
                  test-handle-send-defaults-to-empty-connections)))

;; ============================================================
;; handle-close Tests
;; ============================================================

(defn- test-handle-close-passes-connections-from-state-to-effect []
  (let [state {:ws/connections {1 {:ws/port 1234}}}
        result (ws-actions/handle-close state {:ws/tab-id 1})]
    (.toEqual (expect (first (:uf/fxs result)))
              [:ws/fx.handle-close {1 {:ws/port 1234}} 1])))

(defn- test-handle-close-defaults-to-empty-connections []
  (let [result (ws-actions/handle-close {} {:ws/tab-id 1})]
    (.toEqual (expect (first (:uf/fxs result)))
              [:ws/fx.handle-close {} 1])))

(describe "ws-actions/handle-close"
          (fn []
            (test "passes connections from state to effect"
                  test-handle-close-passes-connections-from-state-to-effect)
            (test "defaults to empty connections when nil"
                  test-handle-close-defaults-to-empty-connections)))
