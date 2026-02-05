(ns background-utils-test
  (:require [background-utils :as bg]
            ["vitest" :refer [describe test expect]]))

;; ============================================================
;; Test Functions
;; ============================================================

;; any-tab-connected?

(defn- test-any-tab-connected-returns-false-for-empty-state []
  (-> (expect (bg/any-tab-connected? {}))
      (.toBe false)))

(defn- test-any-tab-connected-returns-false-when-no-tabs-connected []
  (-> (expect (bg/any-tab-connected? {1 "disconnected" 2 "disconnected"}))
      (.toBe false)))

(defn- test-any-tab-connected-returns-true-when-one-tab-connected []
  (-> (expect (bg/any-tab-connected? {1 "disconnected" 2 "connected"}))
      (.toBe true)))

(defn- test-any-tab-connected-returns-true-when-multiple-tabs-connected []
  (-> (expect (bg/any-tab-connected? {1 "connected" 2 "connected"}))
      (.toBe true)))

(defn- test-any-tab-connected-returns-true-when-only-tab-connected []
  (-> (expect (bg/any-tab-connected? {1 "connected"}))
      (.toBe true)))

;; compute-display-icon-state - no tabs connected

(defn- test-compute-display-icon-returns-disconnected-for-empty-state []
  (-> (expect (bg/compute-display-icon-state {} nil))
      (.toBe "disconnected")))

(defn- test-compute-display-icon-returns-disconnected-when-active-tab-not-in-state []
  (-> (expect (bg/compute-display-icon-state {} 123))
      (.toBe "disconnected")))

(defn- test-compute-display-icon-returns-disconnected-when-active-tab-disconnected []
  (-> (expect (bg/compute-display-icon-state {123 "disconnected"} 123))
      (.toBe "disconnected")))

(defn- test-compute-display-icon-returns-disconnected-when-only-non-active-tabs-exist []
  (-> (expect (bg/compute-display-icon-state {456 "disconnected"} 123))
      (.toBe "disconnected")))

;; compute-display-icon-state - global connected state

(defn- test-compute-display-icon-returns-connected-when-active-tab-connected []
  (-> (expect (bg/compute-display-icon-state {123 "connected"} 123))
      (.toBe "connected")))

(defn- test-compute-display-icon-returns-connected-when-different-tab-connected-active-disconnected-alt []
  (-> (expect (bg/compute-display-icon-state {123 "disconnected" 456 "connected"} 123))
      (.toBe "disconnected")))

(defn- test-compute-display-icon-returns-connected-when-different-tab-connected-active-disconnected []
  (-> (expect (bg/compute-display-icon-state {123 "disconnected" 456 "connected"} 123))
      (.toBe "disconnected")))

(defn- test-compute-display-icon-returns-connected-when-different-tab-connected-active-not-in-state []
  (-> (expect (bg/compute-display-icon-state {456 "connected"} 123))
      (.toBe "disconnected")))

;; get-icon-paths

(defn- test-get-icon-paths-returns-connected-paths-for-connected-keyword []
  (let [paths (bg/get-icon-paths :connected)]
    (-> (expect (aget paths "16")) (.toBe "icons/icon-connected-16.png"))))

(defn- test-get-icon-paths-returns-connected-paths-for-connected-string []
  (let [paths (bg/get-icon-paths "connected")]
    (-> (expect (aget paths "16")) (.toBe "icons/icon-connected-16.png"))))

(defn- test-get-icon-paths-returns-disconnected-paths-for-disconnected-keyword []
  (let [paths (bg/get-icon-paths :disconnected)]
    (-> (expect (aget paths "16")) (.toBe "icons/icon-disconnected-16.png"))))

(defn- test-get-icon-paths-returns-disconnected-paths-for-disconnected-string []
  (let [paths (bg/get-icon-paths "disconnected")]
    (-> (expect (aget paths "16")) (.toBe "icons/icon-disconnected-16.png"))))

(defn- test-get-icon-paths-returns-disconnected-paths-for-nil []
  (let [paths (bg/get-icon-paths nil)]
    (-> (expect (aget paths "16")) (.toBe "icons/icon-disconnected-16.png"))))

(defn- test-get-icon-paths-returns-disconnected-paths-for-unknown-state []
  (let [paths (bg/get-icon-paths "unknown")]
    (-> (expect (aget paths "16")) (.toBe "icons/icon-disconnected-16.png"))))

;; url-origin-allowed?

(defn- test-url-origin-allowed-returns-true-for-url-starting-with-allowed-origin []
  (let [allowed ["https://gist.githubusercontent.com/"
                 "https://raw.githubusercontent.com/"]]
    (-> (expect (bg/url-origin-allowed? "https://gist.githubusercontent.com/foo/bar" allowed))
        (.toBe true))))

(defn- test-url-origin-allowed-returns-true-for-another-allowed-origin []
  (let [allowed ["https://gist.githubusercontent.com/"
                 "https://raw.githubusercontent.com/"]]
    (-> (expect (bg/url-origin-allowed? "https://raw.githubusercontent.com/user/repo/main/script.cljs" allowed))
        (.toBe true))))

(defn- test-url-origin-allowed-returns-false-for-url-not-starting-with-allowed-origin []
  (let [allowed ["https://gist.githubusercontent.com/"
                 "https://raw.githubusercontent.com/"]]
    (-> (expect (bg/url-origin-allowed? "https://example.com/script.cljs" allowed))
        (.toBe false))))

(defn- test-url-origin-allowed-returns-false-for-similar-but-not-matching-origin []
  (let [allowed ["https://gist.githubusercontent.com/"
                 "https://raw.githubusercontent.com/"]]
    (-> (expect (bg/url-origin-allowed? "https://gist.github.com/foo" allowed))
        (.toBe false))))

(defn- test-url-origin-allowed-returns-false-for-empty-allowed-list []
  (-> (expect (bg/url-origin-allowed? "https://gist.githubusercontent.com/foo" []))
      (.toBe false)))

;; find-tab-on-port

(defn- test-find-tab-on-port-returns-nil-for-empty-connections []
  (-> (expect (bg/find-tab-on-port {} 1340 nil))
      (.toBeUndefined)))

(defn- test-find-tab-on-port-finds-tab-on-matching-port []
  (let [connections {1 {:ws/port 1340 :ws/tab-title "Tab 1"}
                     2 {:ws/port 1341 :ws/tab-title "Tab 2"}}]
    (-> (expect (bg/find-tab-on-port connections 1340 nil))
        (.toBe "1"))))

(defn- test-find-tab-on-port-excludes-specified-tab-id []
  (let [connections {1 {:ws/port 1340 :ws/tab-title "Tab 1"}
                     2 {:ws/port 1340 :ws/tab-title "Tab 2"}}]
    ;; Should find tab 2 when excluding tab 1
    (-> (expect (bg/find-tab-on-port connections 1340 "1"))
        (.toBe "2"))))

(defn- test-find-tab-on-port-returns-nil-when-port-not-found []
  (let [connections {1 {:ws/port 1340 :ws/tab-title "Tab 1"}}]
    (-> (expect (bg/find-tab-on-port connections 9999 nil))
        (.toBeUndefined))))

(defn- test-find-tab-on-port-returns-nil-when-only-match-excluded []
  (let [connections {1 {:ws/port 1340 :ws/tab-title "Tab 1"}}]
    (-> (expect (bg/find-tab-on-port connections 1340 "1"))
        (.toBeUndefined))))

;; connections->display-list

(defn- test-connections-display-list-returns-empty-array-for-empty-connections []
  (let [result (bg/connections->display-list {})]
    (-> (expect (count result))
        (.toBe 0))))

(defn- test-connections-display-list-transforms-connections-map-to-sorted-list []
  (let [connections {2 {:ws/port 1341 :ws/tab-title "Second"}
                     1 {:ws/port 1340 :ws/tab-title "First"}}
        result (bg/connections->display-list connections)]
    (-> (expect (count result))
        (.toBe 2))
    ;; First item should have lower port (sorted)
    (-> (expect (:port (first result)))
        (.toBe 1340))
    (-> (expect (:title (first result)))
        (.toBe "First"))
    (-> (expect (:tab-id (first result)))
        (.toBe "1"))))

(defn- test-connections-display-list-uses-unknown-for-missing-title []
  (let [connections {1 {:ws/port 1340}}
        result (bg/connections->display-list connections)]
    (-> (expect (:title (first result)))
        (.toBe "Unknown"))))

(defn- test-connections-display-list-includes-favicon-when-present []
  (let [connections {1 {:ws/port 1340
                        :ws/tab-title "GitHub"
                        :ws/tab-favicon "https://github.com/favicon.ico"}}
        result (bg/connections->display-list connections)]
    (-> (expect (:favicon (first result)))
        (.toBe "https://github.com/favicon.ico"))))

(defn- test-connections-display-list-favicon-nil-when-missing []
  (let [connections {1 {:ws/port 1340 :ws/tab-title "Test"}}
        result (bg/connections->display-list connections)]
    (-> (expect (:favicon (first result)))
        (.toBeUndefined))))

;; tab-in-history?

(defn- test-tab-in-history-returns-true-when-tab-id-in-history []
  (let [history {"123" {:port 1340}
                 "456" {:port 1341}}]
    (-> (expect (bg/tab-in-history? history "123"))
        (.toBe true))))

(defn- test-tab-in-history-returns-false-when-tab-id-not-in-history []
  (let [history {"123" {:port 1340}
                 "456" {:port 1341}}]
    (-> (expect (bg/tab-in-history? history "789"))
        (.toBe false))))

(defn- test-tab-in-history-returns-false-for-empty-history []
  (-> (expect (bg/tab-in-history? {} "123"))
      (.toBe false)))

(defn- test-tab-in-history-returns-false-for-nil-history []
  (-> (expect (bg/tab-in-history? nil "123"))
      (.toBe false)))

;; get-history-port

(defn- test-get-history-port-returns-port-for-existing-tab []
  (let [history {"123" {:port 1340}
                 "456" {:port 1341}}]
    (-> (expect (bg/get-history-port history "123"))
        (.toBe 1340))))

(defn- test-get-history-port-returns-port-for-another-existing-tab []
  (let [history {"123" {:port 1340}
                 "456" {:port 1341}}]
    (-> (expect (bg/get-history-port history "456"))
        (.toBe 1341))))

(defn- test-get-history-port-returns-nil-for-non-existent-tab []
  (let [history {"123" {:port 1340}
                 "456" {:port 1341}}]
    (-> (expect (bg/get-history-port history "789"))
        (.toBeUndefined))))

(defn- test-get-history-port-returns-nil-for-empty-history []
  (-> (expect (bg/get-history-port {} "123"))
      (.toBeUndefined)))

;; ============================================================
;; Test Registration
;; ============================================================

(describe "any-tab-connected?"
          (fn []
            (test "returns false for empty state" test-any-tab-connected-returns-false-for-empty-state)
            (test "returns false when no tabs are connected" test-any-tab-connected-returns-false-when-no-tabs-connected)
            (test "returns true when one tab is connected" test-any-tab-connected-returns-true-when-one-tab-connected)
            (test "returns true when multiple tabs are connected" test-any-tab-connected-returns-true-when-multiple-tabs-connected)
            (test "returns true when only tab is connected" test-any-tab-connected-returns-true-when-only-tab-connected)))

(describe "compute-display-icon-state - no tabs connected"
          (fn []
            (test "returns disconnected for empty state" test-compute-display-icon-returns-disconnected-for-empty-state)
            (test "returns disconnected when active tab not in state" test-compute-display-icon-returns-disconnected-when-active-tab-not-in-state)
            (test "returns disconnected when active tab is disconnected" test-compute-display-icon-returns-disconnected-when-active-tab-disconnected)
            (test "returns disconnected when only non-active tabs exist" test-compute-display-icon-returns-disconnected-when-only-non-active-tabs-exist)))

(describe "compute-display-icon-state - per-tab connected state"
          (fn []
            (test "returns connected when active tab is connected" test-compute-display-icon-returns-connected-when-active-tab-connected)
            (test "returns disconnected when different tab is connected, active is disconnected" test-compute-display-icon-returns-connected-when-different-tab-connected-active-disconnected-alt)
            (test "returns disconnected when different tab is connected, active is disconnected (dup)" test-compute-display-icon-returns-connected-when-different-tab-connected-active-disconnected)
            (test "returns disconnected when different tab is connected, active not in state" test-compute-display-icon-returns-connected-when-different-tab-connected-active-not-in-state)))

(describe "get-icon-paths"
          (fn []
            (test "returns connected paths for :connected keyword" test-get-icon-paths-returns-connected-paths-for-connected-keyword)
            (test "returns connected paths for 'connected' string" test-get-icon-paths-returns-connected-paths-for-connected-string)
            (test "returns disconnected paths for :disconnected keyword" test-get-icon-paths-returns-disconnected-paths-for-disconnected-keyword)
            (test "returns disconnected paths for 'disconnected' string" test-get-icon-paths-returns-disconnected-paths-for-disconnected-string)
            (test "returns disconnected paths for nil" test-get-icon-paths-returns-disconnected-paths-for-nil)
            (test "returns disconnected paths for unknown state" test-get-icon-paths-returns-disconnected-paths-for-unknown-state)))

(describe "url-origin-allowed?"
          (fn []
            (test "returns true for URL starting with allowed origin" test-url-origin-allowed-returns-true-for-url-starting-with-allowed-origin)
            (test "returns true for another allowed origin" test-url-origin-allowed-returns-true-for-another-allowed-origin)
            (test "returns false for URL not starting with any allowed origin" test-url-origin-allowed-returns-false-for-url-not-starting-with-allowed-origin)
            (test "returns false for similar but not matching origin" test-url-origin-allowed-returns-false-for-similar-but-not-matching-origin)
            (test "returns false for empty allowed list" test-url-origin-allowed-returns-false-for-empty-allowed-list)))

(describe "find-tab-on-port"
          (fn []
            (test "returns nil for empty connections" test-find-tab-on-port-returns-nil-for-empty-connections)
            (test "finds tab on matching port" test-find-tab-on-port-finds-tab-on-matching-port)
            (test "excludes specified tab-id" test-find-tab-on-port-excludes-specified-tab-id)
            (test "returns nil when port not found" test-find-tab-on-port-returns-nil-when-port-not-found)
            (test "returns nil when only match is excluded" test-find-tab-on-port-returns-nil-when-only-match-excluded)))

(describe "connections->display-list"
          (fn []
            (test "returns empty array for empty connections" test-connections-display-list-returns-empty-array-for-empty-connections)
            (test "transforms connections map to sorted list" test-connections-display-list-transforms-connections-map-to-sorted-list)
            (test "uses 'Unknown' for missing title" test-connections-display-list-uses-unknown-for-missing-title)
            (test "includes favicon when present" test-connections-display-list-includes-favicon-when-present)
            (test "favicon is nil when missing" test-connections-display-list-favicon-nil-when-missing)))

(describe "tab-in-history?"
          (fn []
            (test "returns true when tab-id is in history" test-tab-in-history-returns-true-when-tab-id-in-history)
            (test "returns false when tab-id is not in history" test-tab-in-history-returns-false-when-tab-id-not-in-history)
            (test "returns false for empty history" test-tab-in-history-returns-false-for-empty-history)
            (test "returns false for nil history" test-tab-in-history-returns-false-for-nil-history)))

(describe "get-history-port"
          (fn []
            (test "returns port for existing tab" test-get-history-port-returns-port-for-existing-tab)
            (test "returns port for another existing tab" test-get-history-port-returns-port-for-another-existing-tab)
            (test "returns nil for non-existent tab" test-get-history-port-returns-nil-for-non-existent-tab)
            (test "returns nil for empty history" test-get-history-port-returns-nil-for-empty-history)))

;; ============================================================
;; decide-auto-connection Tests
;; ============================================================

;; Context shape:
;; {:nav/auto-connect-enabled? bool
;;  :nav/auto-reconnect-enabled? bool
;;  :nav/in-history? bool
;;  :nav/history-port string-or-nil
;;  :nav/saved-port string}

;; Decision shape:
;; {:decision :connect-all|:reconnect|:none
;;  :port string-or-nil}

(defn- test-decide-auto-connection-returns-connect-all-when-enabled []
  (let [ctx {:nav/auto-connect-enabled? true
             :nav/auto-reconnect-enabled? false
             :nav/in-history? false
             :nav/history-port nil
             :nav/saved-port "1340"}
        result (bg/decide-auto-connection ctx)]
    (-> (expect (:decision result))
        (.toBe "connect-all"))
    (-> (expect (:port result))
        (.toBe "1340"))))

(defn- test-decide-auto-connection-connect-all-supersedes-reconnect []
  ;; Even when reconnect conditions are met, connect-all wins
  (let [ctx {:nav/auto-connect-enabled? true
             :nav/auto-reconnect-enabled? true
             :nav/in-history? true
             :nav/history-port "1341"
             :nav/saved-port "1340"}
        result (bg/decide-auto-connection ctx)]
    (-> (expect (:decision result))
        (.toBe "connect-all"))
    ;; Uses saved-port, not history-port
    (-> (expect (:port result))
        (.toBe "1340"))))

(defn- test-decide-auto-connection-returns-reconnect-when-conditions-met []
  (let [ctx {:nav/auto-connect-enabled? false
             :nav/auto-reconnect-enabled? true
             :nav/in-history? true
             :nav/history-port "1341"
             :nav/saved-port "1340"}
        result (bg/decide-auto-connection ctx)]
    (-> (expect (:decision result))
        (.toBe "reconnect"))
    ;; Uses history-port
    (-> (expect (:port result))
        (.toBe "1341"))))

(defn- test-decide-auto-connection-returns-none-when-reconnect-disabled []
  (let [ctx {:nav/auto-connect-enabled? false
             :nav/auto-reconnect-enabled? false
             :nav/in-history? true
             :nav/history-port "1341"
             :nav/saved-port "1340"}
        result (bg/decide-auto-connection ctx)]
    (-> (expect (:decision result))
        (.toBe "none"))))

(defn- test-decide-auto-connection-returns-none-when-not-in-history []
  (let [ctx {:nav/auto-connect-enabled? false
             :nav/auto-reconnect-enabled? true
             :nav/in-history? false
             :nav/history-port nil
             :nav/saved-port "1340"}
        result (bg/decide-auto-connection ctx)]
    (-> (expect (:decision result))
        (.toBe "none"))))

(defn- test-decide-auto-connection-returns-none-when-no-history-port []
  ;; Edge case: in history but port is nil
  (let [ctx {:nav/auto-connect-enabled? false
             :nav/auto-reconnect-enabled? true
             :nav/in-history? true
             :nav/history-port nil
             :nav/saved-port "1340"}
        result (bg/decide-auto-connection ctx)]
    (-> (expect (:decision result))
        (.toBe "none"))))

(defn- test-decide-auto-connection-returns-none-when-all-disabled []
  (let [ctx {:nav/auto-connect-enabled? false
             :nav/auto-reconnect-enabled? false
             :nav/in-history? false
             :nav/history-port nil
             :nav/saved-port "1340"}
        result (bg/decide-auto-connection ctx)]
    (-> (expect (:decision result))
        (.toBe "none"))))

(describe "decide-auto-connection"
          (fn []
            (test "returns connect-all when auto-connect enabled" test-decide-auto-connection-returns-connect-all-when-enabled)
            (test "connect-all supersedes reconnect even when both conditions met" test-decide-auto-connection-connect-all-supersedes-reconnect)
            (test "returns reconnect when conditions met (enabled, in-history, has port)" test-decide-auto-connection-returns-reconnect-when-conditions-met)
            (test "returns none when auto-reconnect disabled" test-decide-auto-connection-returns-none-when-reconnect-disabled)
            (test "returns none when not in history" test-decide-auto-connection-returns-none-when-not-in-history)
            (test "returns none when no history port" test-decide-auto-connection-returns-none-when-no-history-port)
            (test "returns none when all settings disabled" test-decide-auto-connection-returns-none-when-all-disabled)))
