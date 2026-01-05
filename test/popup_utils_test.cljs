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
        (let [scripts [{:script/id "s1" :script/enabled false :script/approved-patterns ["*://a.com/*"]}]
              result (popup-utils/toggle-script-in-list scripts "s1")]
          (-> (expect (:script/enabled (first result)))
              (.toBe true)))))

    (test "disables an enabled script and clears approvals"
      (fn []
        (let [scripts [{:script/id "s1"
                        :script/enabled true
                        :script/approved-patterns ["*://a.com/*" "*://b.com/*"]}]
              result (popup-utils/toggle-script-in-list scripts "s1")]
          (-> (expect (:script/enabled (first result)))
              (.toBe false))
          (-> (expect (count (:script/approved-patterns (first result))))
              (.toBe 0)))))

    (test "does not modify other scripts"
      (fn []
        (let [scripts [{:script/id "s1" :script/enabled true}
                       {:script/id "s2" :script/enabled false}]
              result (popup-utils/toggle-script-in-list scripts "s1")]
          (-> (expect (:script/enabled (second result)))
              (.toBe false)))))))

;; ============================================================
;; approve-pattern-in-list tests
;; ============================================================

(describe "approve-pattern-in-list"
  (fn []
    (test "adds pattern to empty approved-patterns"
      (fn []
        (let [scripts [{:script/id "s1" :script/approved-patterns []}]
              result (popup-utils/approve-pattern-in-list scripts "s1" "*://example.com/*")]
          (-> (expect (count (:script/approved-patterns (first result))))
              (.toBe 1))
          (-> (expect (first (:script/approved-patterns (first result))))
              (.toBe "*://example.com/*")))))

    (test "adds pattern to nil approved-patterns"
      (fn []
        (let [scripts [{:script/id "s1"}]
              result (popup-utils/approve-pattern-in-list scripts "s1" "*://example.com/*")]
          (-> (expect (count (:script/approved-patterns (first result))))
              (.toBe 1)))))

    (test "does not duplicate existing pattern"
      (fn []
        (let [scripts [{:script/id "s1"
                        :script/approved-patterns ["*://example.com/*"]}]
              result (popup-utils/approve-pattern-in-list scripts "s1" "*://example.com/*")]
          (-> (expect (count (:script/approved-patterns (first result))))
              (.toBe 1)))))

    (test "adds new pattern alongside existing"
      (fn []
        (let [scripts [{:script/id "s1"
                        :script/approved-patterns ["*://a.com/*"]}]
              result (popup-utils/approve-pattern-in-list scripts "s1" "*://b.com/*")]
          (-> (expect (count (:script/approved-patterns (first result))))
              (.toBe 2)))))))

;; ============================================================
;; disable-script-in-list tests
;; ============================================================

(describe "disable-script-in-list"
  (fn []
    (test "disables an enabled script"
      (fn []
        (let [scripts [{:script/id "s1" :script/enabled true}]
              result (popup-utils/disable-script-in-list scripts "s1")]
          (-> (expect (:script/enabled (first result)))
              (.toBe false)))))

    (test "keeps disabled script disabled"
      (fn []
        (let [scripts [{:script/id "s1" :script/enabled false}]
              result (popup-utils/disable-script-in-list scripts "s1")]
          (-> (expect (:script/enabled (first result)))
              (.toBe false)))))

    (test "does not modify other scripts"
      (fn []
        (let [scripts [{:script/id "s1" :script/enabled true}
                       {:script/id "s2" :script/enabled true}]
              result (popup-utils/disable-script-in-list scripts "s1")]
          (-> (expect (:script/enabled (second result)))
              (.toBe true)))))))

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
