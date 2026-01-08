(ns registration-test
  (:require ["vitest" :refer [describe test expect]]
            [registration :as reg]))

(describe "needs-early-injection?"
          (fn []
            (test "returns true for enabled document-start script with approved patterns"
                  (fn []
                    (let [script {:script/id "test"
                                  :script/enabled true
                                  :script/run-at "document-start"
                                  :script/approved-patterns ["https://example.com/*"]}]
                      (-> (expect (reg/needs-early-injection? script))
                          (.toBeTruthy)))))

            (test "returns true for enabled document-end script with approved patterns"
                  (fn []
                    (let [script {:script/id "test"
                                  :script/enabled true
                                  :script/run-at "document-end"
                                  :script/approved-patterns ["https://example.com/*"]}]
                      (-> (expect (reg/needs-early-injection? script))
                          (.toBeTruthy)))))

            (test "returns false for document-idle script"
                  (fn []
                    (let [script {:script/id "test"
                                  :script/enabled true
                                  :script/run-at "document-idle"
                                  :script/approved-patterns ["https://example.com/*"]}]
                      (-> (expect (reg/needs-early-injection? script))
                          (.toBeFalsy)))))

            (test "returns false for disabled script"
                  (fn []
                    (let [script {:script/id "test"
                                  :script/enabled false
                                  :script/run-at "document-start"
                                  :script/approved-patterns ["https://example.com/*"]}]
                      (-> (expect (reg/needs-early-injection? script))
                          (.toBeFalsy)))))

            (test "returns false for script without approved patterns"
                  (fn []
                    (let [script {:script/id "test"
                                  :script/enabled true
                                  :script/run-at "document-start"
                                  :script/approved-patterns []}]
                      (-> (expect (reg/needs-early-injection? script))
                          (.toBeFalsy)))))))

(describe "collect-approved-patterns"
          (fn []
            (test "collects unique patterns from multiple scripts"
                  (fn []
                    (let [scripts [{:script/approved-patterns ["https://a.com/*" "https://b.com/*"]}
                                   {:script/approved-patterns ["https://b.com/*" "https://c.com/*"]}]]
                      (-> (expect (reg/collect-approved-patterns scripts))
                          (.toEqual #js ["https://a.com/*" "https://b.com/*" "https://c.com/*"])))))

            (test "returns empty vector for empty scripts list"
                  (fn []
                    (-> (expect (reg/collect-approved-patterns []))
                        (.toEqual #js []))))))

(describe "build-registration"
          (fn []
            (test "builds registration config with patterns"
                  (fn []
                    (let [result (reg/build-registration ["https://example.com/*" "https://test.com/*"])]
                      (-> (expect (.-id result))
                          (.toBe "epupp-early-injection"))
                      (-> (expect (.-matches result))
                          (.toEqual #js ["https://example.com/*" "https://test.com/*"]))
                      (-> (expect (.-js result))
                          (.toEqual #js ["userscript-loader.js"]))
                      (-> (expect (.-runAt result))
                          (.toBe "document_start"))
                      (-> (expect (.-persistAcrossSessions result))
                          (.toBe true)))))

            (test "returns nil for empty patterns"
                  (fn []
                    (-> (expect (reg/build-registration []))
                        (.toBeUndefined))))))


