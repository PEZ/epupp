(ns registration-test
  (:require ["vitest" :refer [describe test expect]]
            [registration :as reg]))

;; ============================================================
;; Test Functions
;; ============================================================

;; needs-early-injection? tests

(defn- test-returns-true-for-enabled-document-start-script-with-patterns []
  (let [script {:script/id "test"
                :script/enabled true
                :script/run-at "document-start"
                :script/match ["https://example.com/*"]}]
    (-> (expect (reg/needs-early-injection? script))
        (.toBeTruthy))))

(defn- test-returns-true-for-enabled-document-end-script-with-patterns []
  (let [script {:script/id "test"
                :script/enabled true
                :script/run-at "document-end"
                :script/match ["https://example.com/*"]}]
    (-> (expect (reg/needs-early-injection? script))
        (.toBeTruthy))))

(defn- test-returns-false-for-document-idle-script []
  (let [script {:script/id "test"
                :script/enabled true
                :script/run-at "document-idle"
                :script/match ["https://example.com/*"]}]
    (-> (expect (reg/needs-early-injection? script))
        (.toBeFalsy))))

(defn- test-returns-false-for-disabled-script []
  (let [script {:script/id "test"
                :script/enabled false
                :script/run-at "document-start"
                :script/match ["https://example.com/*"]}]
    (-> (expect (reg/needs-early-injection? script))
        (.toBeFalsy))))

(defn- test-returns-false-for-script-without-patterns []
  (let [script {:script/id "test"
                :script/enabled true
                :script/run-at "document-start"
                :script/match []}]
    (-> (expect (reg/needs-early-injection? script))
        (.toBeFalsy))))

;; collect-patterns tests

(defn- test-collects-unique-patterns-from-multiple-scripts []
  (let [scripts [{:script/match ["https://a.com/*" "https://b.com/*"]}
                 {:script/match ["https://b.com/*" "https://c.com/*"]}]]
    (-> (expect (reg/collect-patterns scripts))
        (.toEqual #js ["https://a.com/*" "https://b.com/*" "https://c.com/*"]))))

(defn- test-returns-empty-vector-for-empty-scripts-list []
  (-> (expect (reg/collect-patterns []))
      (.toEqual #js [])))

;; build-registration tests

(defn- test-builds-registration-config-with-patterns []
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
        (.toBe true))))

(defn- test-returns-nil-for-empty-patterns []
  (-> (expect (reg/build-registration []))
      (.toBeUndefined)))

;; ============================================================
;; Test Registration
;; ============================================================

(describe "needs-early-injection?"
          (fn []
            (test "returns true for enabled document-start script with patterns"
                  test-returns-true-for-enabled-document-start-script-with-patterns)
            (test "returns true for enabled document-end script with patterns"
                  test-returns-true-for-enabled-document-end-script-with-patterns)
            (test "returns false for document-idle script"
                  test-returns-false-for-document-idle-script)
            (test "returns false for disabled script"
                  test-returns-false-for-disabled-script)
            (test "returns false for script without patterns"
                  test-returns-false-for-script-without-patterns)))

(describe "collect-patterns"
          (fn []
            (test "collects unique patterns from multiple scripts"
                  test-collects-unique-patterns-from-multiple-scripts)
            (test "returns empty vector for empty scripts list"
                  test-returns-empty-vector-for-empty-scripts-list)))

(describe "build-registration"
          (fn []
            (test "builds registration config with patterns"
                  test-builds-registration-config-with-patterns)
            (test "returns nil for empty patterns"
                  test-returns-nil-for-empty-patterns)))


