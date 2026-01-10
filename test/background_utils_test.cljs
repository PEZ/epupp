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

(describe "count-pending-for-url"
          (fn []
            (let [always-matches (fn [_url _script] "pattern")
                  never-matches (fn [_url _script] nil)
                  always-approved (fn [_script _pattern] true)
                  never-approved (fn [_script _pattern] false)
                  scripts [{:script/id "s1" :script/enabled true}
                           {:script/id "s2" :script/enabled true}
                           {:script/id "s3" :script/enabled true}]]

              (test "returns 0 for nil url"
                    (fn []
                      (-> (expect (bg/count-pending-for-url nil scripts always-matches never-approved))
                          (.toBe 0))))

              (test "returns 0 for empty url"
                    (fn []
                      (-> (expect (bg/count-pending-for-url "" scripts always-matches never-approved))
                          (.toBe 0))))

              (test "returns 0 when no scripts match"
                    (fn []
                      (-> (expect (bg/count-pending-for-url "https://example.com" scripts never-matches never-approved))
                          (.toBe 0))))

              (test "returns 0 when all matching scripts are approved"
                    (fn []
                      (-> (expect (bg/count-pending-for-url "https://example.com" scripts always-matches always-approved))
                          (.toBe 0))))

              (test "counts scripts that match but are not approved"
                    (fn []
                      (-> (expect (bg/count-pending-for-url "https://example.com" scripts always-matches never-approved))
                          (.toBe 3))))

              (test "counts only unapproved scripts"
                    (fn []
                      (let [selective-match (fn [_url script]
                                              (when (contains? #{"s1" "s2"} (:script/id script))
                                                "pattern"))]
                        (-> (expect (bg/count-pending-for-url "https://example.com" scripts selective-match never-approved))
                            (.toBe 2))))))))

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
