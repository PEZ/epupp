(ns popup-utils-test
  (:require ["vitest" :as vt]
            [popup-utils :as popup-utils]))

;; ============================================================
;; status-class tests
;; ============================================================

(vt/describe "status-class"
  (fn []
    (vt/test "returns nil for nil status"
      (fn []
        (-> (vt/expect (popup-utils/status-class nil))
            (.toBeUndefined))))

    (vt/test "returns failed class for Failed prefix"
      (fn []
        (-> (vt/expect (popup-utils/status-class "Failed: connection error"))
            (.toBe "status status-failed"))))

    (vt/test "returns failed class for Error prefix"
      (fn []
        (-> (vt/expect (popup-utils/status-class "Error: timeout"))
            (.toBe "status status-failed"))))

    (vt/test "returns pending class for status ending with ellipsis"
      (fn []
        (-> (vt/expect (popup-utils/status-class "Connecting..."))
            (.toBe "status status-pending"))))

    (vt/test "returns pending class for not connected status"
      (fn []
        (-> (vt/expect (popup-utils/status-class "REPL not connected yet"))
            (.toBe "status status-pending"))))

    (vt/test "returns base status class for success status"
      (fn []
        (-> (vt/expect (popup-utils/status-class "Connected to ws://localhost:1340"))
            (.toBe "status"))))))

;; ============================================================
;; generate-server-cmd tests
;; ============================================================

(vt/describe "generate-server-cmd"
  (fn []
    (vt/test "generates command with custom ports"
      (fn []
        (let [cmd (popup-utils/generate-server-cmd {:deps-string "{:deps {}}"
                                                    :nrepl-port "1234"
                                                    :ws-port "5678"})]
          (-> (vt/expect cmd)
              (.toContain ":nrepl-port 1234"))
          (-> (vt/expect cmd)
              (.toContain ":websocket-port 5678"))
          (-> (vt/expect cmd)
              (.toContain "bb -Sdeps"))
          (-> (vt/expect cmd)
              (.toContain "server/start!")))))

    (vt/test "generates command with default ports"
      (fn []
        (let [cmd (popup-utils/generate-server-cmd {:deps-string "{:deps {}}"
                                                    :nrepl-port "1339"
                                                    :ws-port "1340"})]
          (-> (vt/expect cmd)
              (.toContain ":nrepl-port 1339"))
          (-> (vt/expect cmd)
              (.toContain ":websocket-port 1340")))))))

;; ============================================================
;; toggle-script-in-list tests
;; ============================================================

(vt/describe "toggle-script-in-list"
  (fn []
    (vt/test "enables a disabled script"
      (fn []
        (let [scripts [{:script/id "s1" :script/enabled false :script/approved-patterns ["*://a.com/*"]}]
              result (popup-utils/toggle-script-in-list scripts "s1")]
          (-> (vt/expect (:script/enabled (first result)))
              (.toBe true)))))

    (vt/test "disables an enabled script and clears approvals"
      (fn []
        (let [scripts [{:script/id "s1"
                        :script/enabled true
                        :script/approved-patterns ["*://a.com/*" "*://b.com/*"]}]
              result (popup-utils/toggle-script-in-list scripts "s1")]
          (-> (vt/expect (:script/enabled (first result)))
              (.toBe false))
          (-> (vt/expect (count (:script/approved-patterns (first result))))
              (.toBe 0)))))

    (vt/test "does not modify other scripts"
      (fn []
        (let [scripts [{:script/id "s1" :script/enabled true}
                       {:script/id "s2" :script/enabled false}]
              result (popup-utils/toggle-script-in-list scripts "s1")]
          (-> (vt/expect (:script/enabled (second result)))
              (.toBe false)))))))

;; ============================================================
;; approve-pattern-in-list tests
;; ============================================================

(vt/describe "approve-pattern-in-list"
  (fn []
    (vt/test "adds pattern to empty approved-patterns"
      (fn []
        (let [scripts [{:script/id "s1" :script/approved-patterns []}]
              result (popup-utils/approve-pattern-in-list scripts "s1" "*://example.com/*")]
          (-> (vt/expect (count (:script/approved-patterns (first result))))
              (.toBe 1))
          (-> (vt/expect (first (:script/approved-patterns (first result))))
              (.toBe "*://example.com/*")))))

    (vt/test "adds pattern to nil approved-patterns"
      (fn []
        (let [scripts [{:script/id "s1"}]
              result (popup-utils/approve-pattern-in-list scripts "s1" "*://example.com/*")]
          (-> (vt/expect (count (:script/approved-patterns (first result))))
              (.toBe 1)))))

    (vt/test "does not duplicate existing pattern"
      (fn []
        (let [scripts [{:script/id "s1"
                        :script/approved-patterns ["*://example.com/*"]}]
              result (popup-utils/approve-pattern-in-list scripts "s1" "*://example.com/*")]
          (-> (vt/expect (count (:script/approved-patterns (first result))))
              (.toBe 1)))))

    (vt/test "adds new pattern alongside existing"
      (fn []
        (let [scripts [{:script/id "s1"
                        :script/approved-patterns ["*://a.com/*"]}]
              result (popup-utils/approve-pattern-in-list scripts "s1" "*://b.com/*")]
          (-> (vt/expect (count (:script/approved-patterns (first result))))
              (.toBe 2)))))))

;; ============================================================
;; disable-script-in-list tests
;; ============================================================

(vt/describe "disable-script-in-list"
  (fn []
    (vt/test "disables an enabled script"
      (fn []
        (let [scripts [{:script/id "s1" :script/enabled true}]
              result (popup-utils/disable-script-in-list scripts "s1")]
          (-> (vt/expect (:script/enabled (first result)))
              (.toBe false)))))

    (vt/test "keeps disabled script disabled"
      (fn []
        (let [scripts [{:script/id "s1" :script/enabled false}]
              result (popup-utils/disable-script-in-list scripts "s1")]
          (-> (vt/expect (:script/enabled (first result)))
              (.toBe false)))))

    (vt/test "does not modify other scripts"
      (fn []
        (let [scripts [{:script/id "s1" :script/enabled true}
                       {:script/id "s2" :script/enabled true}]
              result (popup-utils/disable-script-in-list scripts "s1")]
          (-> (vt/expect (:script/enabled (second result)))
              (.toBe true)))))))

;; ============================================================
;; remove-script-from-list tests
;; ============================================================

(vt/describe "remove-script-from-list"
  (fn []
    (vt/test "removes script by id"
      (fn []
        (let [scripts [{:script/id "s1"} {:script/id "s2"}]
              result (popup-utils/remove-script-from-list scripts "s1")]
          (-> (vt/expect (count result))
              (.toBe 1))
          (-> (vt/expect (:script/id (first result)))
              (.toBe "s2")))))

    (vt/test "returns empty list when removing last script"
      (fn []
        (let [scripts [{:script/id "s1"}]
              result (popup-utils/remove-script-from-list scripts "s1")]
          (-> (vt/expect (count result))
              (.toBe 0)))))

    (vt/test "returns unchanged list for non-existent id"
      (fn []
        (let [scripts [{:script/id "s1"} {:script/id "s2"}]
              result (popup-utils/remove-script-from-list scripts "s3")]
          (-> (vt/expect (count result))
              (.toBe 2)))))))
