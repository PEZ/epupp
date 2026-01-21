(ns background-utils-test
  (:require [background-utils :as bg]
            ["vitest" :refer [describe test expect]]))

(describe "any-tab-connected?"
          (fn []
            (test "returns false for empty state"
                  (fn []
                    (-> (expect (bg/any-tab-connected? {}))
                        (.toBe false))))

            (test "returns false when no tabs are connected"
                  (fn []
                    (-> (expect (bg/any-tab-connected? {1 "disconnected" 2 "injected"}))
                        (.toBe false))))

            (test "returns true when one tab is connected"
                  (fn []
                    (-> (expect (bg/any-tab-connected? {1 "disconnected" 2 "connected"}))
                        (.toBe true))))

            (test "returns true when multiple tabs are connected"
                  (fn []
                    (-> (expect (bg/any-tab-connected? {1 "connected" 2 "connected"}))
                        (.toBe true))))

            (test "returns true when only tab is connected"
                  (fn []
                    (-> (expect (bg/any-tab-connected? {1 "connected"}))
                        (.toBe true))))))

(describe "compute-display-icon-state - no tabs connected"
          (fn []
            (test "returns disconnected for empty state"
                  (fn []
                    (-> (expect (bg/compute-display-icon-state {} nil))
                        (.toBe "disconnected"))))

            (test "returns disconnected when active tab not in state"
                  (fn []
                    (-> (expect (bg/compute-display-icon-state {} 123))
                        (.toBe "disconnected"))))

            (test "returns disconnected when active tab is disconnected"
                  (fn []
                    (-> (expect (bg/compute-display-icon-state {123 "disconnected"} 123))
                        (.toBe "disconnected"))))

            (test "returns injected when active tab is injected"
                  (fn []
                    (-> (expect (bg/compute-display-icon-state {123 "injected"} 123))
                        (.toBe "injected"))))

            (test "returns disconnected when different tab is injected"
                  (fn []
                    (-> (expect (bg/compute-display-icon-state {456 "injected"} 123))
                        (.toBe "disconnected"))))))

(describe "compute-display-icon-state - global connected state"
          (fn []
            (test "returns connected when active tab is connected"
                  (fn []
                    (-> (expect (bg/compute-display-icon-state {123 "connected"} 123))
                        (.toBe "connected"))))

            (test "returns connected when different tab is connected, active is injected"
                  (fn []
                    (-> (expect (bg/compute-display-icon-state {123 "injected" 456 "connected"} 123))
                        (.toBe "connected"))))

            (test "returns connected when different tab is connected, active is disconnected"
                  (fn []
                    (-> (expect (bg/compute-display-icon-state {123 "disconnected" 456 "connected"} 123))
                        (.toBe "connected"))))

            (test "returns connected when different tab is connected, active not in state"
                  (fn []
                    (-> (expect (bg/compute-display-icon-state {456 "connected"} 123))
                        (.toBe "connected"))))))

(describe "get-icon-paths"
          (fn []
            (test "returns connected paths for :connected keyword"
                  (fn []
                    (let [paths (bg/get-icon-paths :connected)]
                      (-> (expect (aget paths "16")) (.toBe "icons/icon-connected-16.png")))))

            (test "returns connected paths for 'connected' string"
                  (fn []
                    (let [paths (bg/get-icon-paths "connected")]
                      (-> (expect (aget paths "16")) (.toBe "icons/icon-connected-16.png")))))

            (test "returns injected paths for :injected keyword"
                  (fn []
                    (let [paths (bg/get-icon-paths :injected)]
                      (-> (expect (aget paths "16")) (.toBe "icons/icon-injected-16.png")))))

            (test "returns injected paths for 'injected' string"
                  (fn []
                    (let [paths (bg/get-icon-paths "injected")]
                      (-> (expect (aget paths "16")) (.toBe "icons/icon-injected-16.png")))))

            (test "returns disconnected paths for :disconnected keyword"
                  (fn []
                    (let [paths (bg/get-icon-paths :disconnected)]
                      (-> (expect (aget paths "16")) (.toBe "icons/icon-disconnected-16.png")))))

            (test "returns disconnected paths for 'disconnected' string"
                  (fn []
                    (let [paths (bg/get-icon-paths "disconnected")]
                      (-> (expect (aget paths "16")) (.toBe "icons/icon-disconnected-16.png")))))

            (test "returns disconnected paths for nil"
                  (fn []
                    (let [paths (bg/get-icon-paths nil)]
                      (-> (expect (aget paths "16")) (.toBe "icons/icon-disconnected-16.png")))))

            (test "returns disconnected paths for unknown state"
                  (fn []
                    (let [paths (bg/get-icon-paths "unknown")]
                      (-> (expect (aget paths "16")) (.toBe "icons/icon-disconnected-16.png")))))))

(describe "url-origin-allowed?"
          (fn []
            (let [allowed ["https://gist.githubusercontent.com/"
                           "https://raw.githubusercontent.com/"]]

              (test "returns true for URL starting with allowed origin"
                    (fn []
                      (-> (expect (bg/url-origin-allowed? "https://gist.githubusercontent.com/foo/bar" allowed))
                          (.toBe true))))

              (test "returns true for another allowed origin"
                    (fn []
                      (-> (expect (bg/url-origin-allowed? "https://raw.githubusercontent.com/user/repo/main/script.cljs" allowed))
                          (.toBe true))))

              (test "returns false for URL not starting with any allowed origin"
                    (fn []
                      (-> (expect (bg/url-origin-allowed? "https://example.com/script.cljs" allowed))
                          (.toBe false))))

              (test "returns false for similar but not matching origin"
                    (fn []
                      (-> (expect (bg/url-origin-allowed? "https://gist.github.com/foo" allowed))
                          (.toBe false))))

              (test "returns false for empty allowed list"
                    (fn []
                      (-> (expect (bg/url-origin-allowed? "https://gist.githubusercontent.com/foo" []))
                          (.toBe false)))))))

(describe "find-tab-on-port"
          (fn []
            (test "returns nil for empty connections"
                  (fn []
                    (-> (expect (bg/find-tab-on-port {} 1340 nil))
                        (.toBeUndefined))))

            (test "finds tab on matching port"
                  (fn []
                    (let [connections {1 {:ws/port 1340 :ws/tab-title "Tab 1"}
                                       2 {:ws/port 1341 :ws/tab-title "Tab 2"}}]
                      (-> (expect (bg/find-tab-on-port connections 1340 nil))
                          (.toBe "1")))))

            (test "excludes specified tab-id"
                  (fn []
                    (let [connections {1 {:ws/port 1340 :ws/tab-title "Tab 1"}
                                       2 {:ws/port 1340 :ws/tab-title "Tab 2"}}]
                      ;; Should find tab 2 when excluding tab 1
                      (-> (expect (bg/find-tab-on-port connections 1340 "1"))
                          (.toBe "2")))))

            (test "returns nil when port not found"
                  (fn []
                    (let [connections {1 {:ws/port 1340 :ws/tab-title "Tab 1"}}]
                      (-> (expect (bg/find-tab-on-port connections 9999 nil))
                          (.toBeUndefined)))))

            (test "returns nil when only match is excluded"
                  (fn []
                    (let [connections {1 {:ws/port 1340 :ws/tab-title "Tab 1"}}]
                      (-> (expect (bg/find-tab-on-port connections 1340 "1"))
                          (.toBeUndefined)))))))

(describe "connections->display-list"
          (fn []
            (test "returns empty array for empty connections"
                  (fn []
                    (let [result (bg/connections->display-list {})]
                      (-> (expect (count result))
                          (.toBe 0)))))

            (test "transforms connections map to sorted list"
                  (fn []
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
                          (.toBe "1")))))

            (test "uses 'Unknown' for missing title"
                  (fn []
                    (let [connections {1 {:ws/port 1340}}
                          result (bg/connections->display-list connections)]
                      (-> (expect (:title (first result)))
                          (.toBe "Unknown")))))

            (test "includes favicon when present"
                  (fn []
                    (let [connections {1 {:ws/port 1340
                                          :ws/tab-title "GitHub"
                                          :ws/tab-favicon "https://github.com/favicon.ico"}}
                          result (bg/connections->display-list connections)]
                      (-> (expect (:favicon (first result)))
                          (.toBe "https://github.com/favicon.ico")))))

            (test "favicon is nil when missing"
                  (fn []
                    (let [connections {1 {:ws/port 1340 :ws/tab-title "Test"}}
                          result (bg/connections->display-list connections)]
                      (-> (expect (:favicon (first result)))
                          (.toBeUndefined)))))))

(describe "tab-in-history?"
          (fn []
            (let [history {"123" {:port 1340}
                           "456" {:port 1341}}]

              (test "returns true when tab-id is in history"
                    (fn []
                      (-> (expect (bg/tab-in-history? history "123"))
                          (.toBe true))))

              (test "returns false when tab-id is not in history"
                    (fn []
                      (-> (expect (bg/tab-in-history? history "789"))
                          (.toBe false))))

              (test "returns false for empty history"
                    (fn []
                      (-> (expect (bg/tab-in-history? {} "123"))
                          (.toBe false))))

              (test "returns false for nil history"
                    (fn []
                      (-> (expect (bg/tab-in-history? nil "123"))
                          (.toBe false)))))))

(describe "get-history-port"
          (fn []
            (let [history {"123" {:port 1340}
                           "456" {:port 1341}}]

              (test "returns port for existing tab"
                    (fn []
                      (-> (expect (bg/get-history-port history "123"))
                          (.toBe 1340))))

              (test "returns port for another existing tab"
                    (fn []
                      (-> (expect (bg/get-history-port history "456"))
                          (.toBe 1341))))

              (test "returns nil for non-existent tab"
                    (fn []
                      (-> (expect (bg/get-history-port history "789"))
                          (.toBeUndefined))))

              (test "returns nil for empty history"
                    (fn []
                      (-> (expect (bg/get-history-port {} "123"))
                          (.toBeUndefined)))))))
