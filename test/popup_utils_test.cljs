(ns popup-utils-test
  (:require ["vitest" :refer [describe test expect]]
            [popup-utils :as popup-utils]))

;; ============================================================
;; status-class tests
;; ============================================================

(describe "status-class"
          (fn []
            (test "returns nil for nil status"
                  (fn []
                    (-> (expect (popup-utils/status-class nil))
                        (.toBeUndefined))))

            (test "returns failed class for Failed prefix"
                  (fn []
                    (-> (expect (popup-utils/status-class "Failed: connection error"))
                        (.toBe "status status-failed"))))

            (test "returns failed class for Error prefix"
                  (fn []
                    (-> (expect (popup-utils/status-class "Error: timeout"))
                        (.toBe "status status-failed"))))

            (test "returns pending class for status ending with ellipsis"
                  (fn []
                    (-> (expect (popup-utils/status-class "Connecting..."))
                        (.toBe "status status-pending"))))

            (test "returns pending class for not connected status"
                  (fn []
                    (-> (expect (popup-utils/status-class "REPL not connected yet"))
                        (.toBe "status status-pending"))))

            (test "returns base status class for success status"
                  (fn []
                    (-> (expect (popup-utils/status-class "Connected to ws://localhost:1340"))
                        (.toBe "status"))))))

;; ============================================================
;; generate-server-cmd tests
;; ============================================================

(describe "generate-server-cmd"
          (fn []
            (test "generates command with custom ports"
                  (fn []
                    (let [cmd (popup-utils/generate-server-cmd {:deps-string "{:deps {}}"
                                                                :nrepl-port "1234"
                                                                :ws-port "5678"})]
                      (-> (expect cmd)
                          (.toContain ":nrepl-port 1234"))
                      (-> (expect cmd)
                          (.toContain ":websocket-port 5678"))
                      (-> (expect cmd)
                          (.toContain "bb -Sdeps"))
                      (-> (expect cmd)
                          (.toContain "server/start!")))))

            (test "generates command with default ports"
                  (fn []
                    (let [cmd (popup-utils/generate-server-cmd {:deps-string "{:deps {}}"
                                                                :nrepl-port "1339"
                                                                :ws-port "1340"})]
                      (-> (expect cmd)
                          (.toContain ":nrepl-port 1339"))
                      (-> (expect cmd)
                          (.toContain ":websocket-port 1340")))))))

;; ============================================================
;; toggle-script-in-list tests
;; ============================================================

(describe "toggle-script-in-list"
          (fn []
            (test "enables a disabled script"
                  (fn []
                    (let [scripts [{:script/id "s1" :script/enabled false}]
                          result (popup-utils/toggle-script-in-list scripts "s1")]
                      (-> (expect (:script/enabled (first result)))
                          (.toBe true)))))

            (test "disables an enabled script"
                  (fn []
                    (let [scripts [{:script/id "s1" :script/enabled true}]
                          result (popup-utils/toggle-script-in-list scripts "s1")]
                      (-> (expect (:script/enabled (first result)))
                          (.toBe false)))))

            (test "does not modify other scripts"
                  (fn []
                    (let [scripts [{:script/id "s1" :script/enabled true}
                                   {:script/id "s2" :script/enabled false}]
                          result (popup-utils/toggle-script-in-list scripts "s1")]
                      (-> (expect (:script/enabled (second result)))
                          (.toBe false)))))))

;; ============================================================
;; remove-script-from-list tests
;; ============================================================

(describe "remove-script-from-list"
          (fn []
            (test "removes script by id"
                  (fn []
                    (let [scripts [{:script/id "s1"} {:script/id "s2"}]
                          result (popup-utils/remove-script-from-list scripts "s1")]
                      (-> (expect (count result))
                          (.toBe 1))
                      (-> (expect (:script/id (first result)))
                          (.toBe "s2")))))

            (test "returns empty list when removing last script"
                  (fn []
                    (let [scripts [{:script/id "s1"}]
                          result (popup-utils/remove-script-from-list scripts "s1")]
                      (-> (expect (count result))
                          (.toBe 0)))))

            (test "returns unchanged list for non-existent id"
                  (fn []
                    (let [scripts [{:script/id "s1"} {:script/id "s2"}]
                          result (popup-utils/remove-script-from-list scripts "s3")]
                      (-> (expect (count result))
                          (.toBe 2)))))))

;; ============================================================
;; valid-origin? tests
;; ============================================================

(describe "valid-origin?"
          (fn []
            (test "accepts https:// with trailing slash"
                  (fn []
                    (-> (expect (popup-utils/valid-origin? "https://example.com/"))
                        (.toBe true))))

            (test "accepts http:// with trailing slash"
                  (fn []
                    (-> (expect (popup-utils/valid-origin? "http://localhost/"))
                        (.toBe true))))

            (test "accepts https:// with trailing colon (port prefix)"
                  (fn []
                    (-> (expect (popup-utils/valid-origin? "http://localhost:"))
                        (.toBe true))))

            (test "rejects URL without trailing slash or colon"
                  (fn []
                    (-> (expect (popup-utils/valid-origin? "https://example.com"))
                        (.toBeFalsy))))

            (test "rejects ftp:// protocol"
                  (fn []
                    (-> (expect (popup-utils/valid-origin? "ftp://example.com/"))
                        (.toBeFalsy))))

            (test "rejects empty string"
                  (fn []
                    (-> (expect (popup-utils/valid-origin? ""))
                        (.toBeFalsy))))

            (test "rejects nil"
                  (fn []
                    (-> (expect (popup-utils/valid-origin? nil))
                        (.toBeFalsy))))

            (test "rejects whitespace-only string"
                  (fn []
                    (-> (expect (popup-utils/valid-origin? "   "))
                        (.toBeFalsy))))

            (test "trims whitespace before validation"
                  (fn []
                    (-> (expect (popup-utils/valid-origin? "  https://example.com/  "))
                        (.toBe true))))))

;; ============================================================
;; origin-already-exists? tests
;; ============================================================

(describe "origin-already-exists?"
          (fn []
            (test "returns true when origin is in default list"
                  (fn []
                    (-> (expect (popup-utils/origin-already-exists?
                                 "https://github.com/"
                                 ["https://github.com/" "https://gitlab.com/"]
                                 []))
                        (.toBe true))))

            (test "returns true when origin is in user list"
                  (fn []
                    (-> (expect (popup-utils/origin-already-exists?
                                 "https://custom.com/"
                                 ["https://github.com/"]
                                 ["https://custom.com/"]))
                        (.toBe true))))

            (test "returns false when origin is in neither list"
                  (fn []
                    (-> (expect (popup-utils/origin-already-exists?
                                 "https://new.com/"
                                 ["https://github.com/"]
                                 ["https://custom.com/"]))
                        (.toBeFalsy))))

            (test "trims whitespace before comparison"
                  (fn []
                    (-> (expect (popup-utils/origin-already-exists?
                                 "  https://github.com/  "
                                 ["https://github.com/"]
                                 []))
                        (.toBe true))))))

;; ============================================================
;; sort-scripts-for-display tests
;; ============================================================

(describe "sort-scripts-for-display"
          (fn []
            (let [builtin? #(and (:script/id %) (.startsWith (:script/id %) "epupp-builtin-"))]
              (test "sorts user scripts alphabetically by name"
                    (fn []
                      (let [scripts [{:script/id "s1" :script/name "zebra.cljs"}
                                     {:script/id "s2" :script/name "alpha.cljs"}
                                     {:script/id "s3" :script/name "mike.cljs"}]
                            result (vec (popup-utils/sort-scripts-for-display scripts builtin?))]
                        (-> (expect (:script/name (first result)))
                            (.toBe "alpha.cljs"))
                        (-> (expect (:script/name (second result)))
                            (.toBe "mike.cljs"))
                        (-> (expect (:script/name (nth result 2)))
                            (.toBe "zebra.cljs")))))

              (test "places built-in scripts after user scripts"
                    (fn []
                      (let [scripts [{:script/id "epupp-builtin-gist" :script/name "Gist Installer"}
                                     {:script/id "s1" :script/name "my_script.cljs"}]
                            result (vec (popup-utils/sort-scripts-for-display scripts builtin?))]
                        (-> (expect (:script/name (first result)))
                            (.toBe "my_script.cljs"))
                        (-> (expect (:script/name (second result)))
                            (.toBe "Gist Installer")))))

              (test "sorts built-in scripts alphabetically among themselves"
                    (fn []
                      (let [scripts [{:script/id "epupp-builtin-zzz" :script/name "Zzz Builtin"}
                                     {:script/id "epupp-builtin-aaa" :script/name "Aaa Builtin"}
                                     {:script/id "s1" :script/name "user.cljs"}]
                            result (vec (popup-utils/sort-scripts-for-display scripts builtin?))]
                        (-> (expect (:script/name (first result)))
                            (.toBe "user.cljs"))
                        (-> (expect (:script/name (second result)))
                            (.toBe "Aaa Builtin"))
                        (-> (expect (:script/name (nth result 2)))
                            (.toBe "Zzz Builtin")))))

              (test "sorts case-insensitively"
                    (fn []
                      (let [scripts [{:script/id "s1" :script/name "Zebra.cljs"}
                                     {:script/id "s2" :script/name "alpha.cljs"}]
                            result (vec (popup-utils/sort-scripts-for-display scripts builtin?))]
                        (-> (expect (:script/name (first result)))
                            (.toBe "alpha.cljs"))
                        (-> (expect (:script/name (second result)))
                            (.toBe "Zebra.cljs")))))

              (test "handles empty list"
                    (fn []
                      (let [result (vec (popup-utils/sort-scripts-for-display [] builtin?))]
                        (-> (expect (count result))
                            (.toBe 0)))))

              (test "handles list with only built-ins"
                    (fn []
                      (let [scripts [{:script/id "epupp-builtin-b" :script/name "Beta"}
                                     {:script/id "epupp-builtin-a" :script/name "Alpha"}]
                            result (vec (popup-utils/sort-scripts-for-display scripts builtin?))]
                        (-> (expect (count result))
                            (.toBe 2))
                        (-> (expect (:script/name (first result)))
                            (.toBe "Alpha"))))))))
