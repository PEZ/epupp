(ns popup-utils-test
  (:require ["vitest" :refer [describe test expect]]
            [popup-utils :as popup-utils]))

;; ============================================================
;; Test Functions
;; ============================================================

;; status-class tests

(defn- test-returns-nil-for-nil-status []
  (-> (expect (popup-utils/status-class nil))
      (.toBeUndefined)))

(defn- test-returns-failed-class-for-failed-prefix []
  (-> (expect (popup-utils/status-class "Failed: connection error"))
      (.toBe "status status-failed")))

(defn- test-returns-failed-class-for-error-prefix []
  (-> (expect (popup-utils/status-class "Error: timeout"))
      (.toBe "status status-failed")))

(defn- test-returns-pending-class-for-status-ending-with-ellipsis []
  (-> (expect (popup-utils/status-class "Connecting..."))
      (.toBe "status status-pending")))

(defn- test-returns-pending-class-for-not-connected-status []
  (-> (expect (popup-utils/status-class "REPL not connected yet"))
      (.toBe "status status-pending")))

(defn- test-returns-base-status-class-for-success-status []
  (-> (expect (popup-utils/status-class "Connected to ws://localhost:1340"))
      (.toBe "status")))

;; generate-server-cmd tests

(defn- test-generates-command-with-custom-ports []
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
        (.toContain "server/start!"))))

(defn- test-generates-command-with-default-ports []
  (let [cmd (popup-utils/generate-server-cmd {:deps-string "{:deps {}}"
                                              :nrepl-port "1339"
                                              :ws-port "1340"})]
    (-> (expect cmd)
        (.toContain ":nrepl-port 1339"))
    (-> (expect cmd)
        (.toContain ":websocket-port 1340"))))

;; toggle-script-in-list tests

(defn- test-enables-a-disabled-script []
  (let [scripts [{:script/id "s1" :script/enabled false}]
        result (popup-utils/toggle-script-in-list scripts "s1")]
    (-> (expect (:script/enabled (first result)))
        (.toBe true))))

(defn- test-disables-an-enabled-script []
  (let [scripts [{:script/id "s1" :script/enabled true}]
        result (popup-utils/toggle-script-in-list scripts "s1")]
    (-> (expect (:script/enabled (first result)))
        (.toBe false))))

(defn- test-does-not-modify-other-scripts []
  (let [scripts [{:script/id "s1" :script/enabled true}
                 {:script/id "s2" :script/enabled false}]
        result (popup-utils/toggle-script-in-list scripts "s1")]
    (-> (expect (:script/enabled (second result)))
        (.toBe false))))

;; remove-script-from-list tests

(defn- test-removes-script-by-id []
  (let [scripts [{:script/id "s1"} {:script/id "s2"}]
        result (popup-utils/remove-script-from-list scripts "s1")]
    (-> (expect (count result))
        (.toBe 1))
    (-> (expect (:script/id (first result)))
        (.toBe "s2"))))

(defn- test-returns-empty-list-when-removing-last-script []
  (let [scripts [{:script/id "s1"}]
        result (popup-utils/remove-script-from-list scripts "s1")]
    (-> (expect (count result))
        (.toBe 0))))

(defn- test-returns-unchanged-list-for-non-existent-id []
  (let [scripts [{:script/id "s1"} {:script/id "s2"}]
        result (popup-utils/remove-script-from-list scripts "s3")]
    (-> (expect (count result))
        (.toBe 2))))

;; valid-origin? tests
;; Note: valid-origin? validates Web Installer Site patterns (glob patterns or complete URLs)

(defn- test-accepts-glob-pattern-with-wildcard []
  (-> (expect (popup-utils/valid-origin? "https://example.com/*"))
      (.toBe true)))

(defn- test-accepts-http-glob-pattern []
  (-> (expect (popup-utils/valid-origin? "http://localhost/*"))
      (.toBe true)))

(defn- test-accepts-complex-glob-pattern []
  (-> (expect (popup-utils/valid-origin? "https://gist.github.com/*/gist/*"))
      (.toBe true)))

(defn- test-accepts-complete-url-with-path []
  (-> (expect (popup-utils/valid-origin? "https://example.com/some/page"))
      (.toBe true)))

(defn- test-rejects-url-without-wildcard-or-path []
  (-> (expect (popup-utils/valid-origin? "https://example.com/"))
      (.toBeFalsy)))

(defn- test-rejects-url-without-trailing-slash []
  (-> (expect (popup-utils/valid-origin? "https://example.com"))
      (.toBeFalsy)))

(defn- test-rejects-ftp-protocol []
  (-> (expect (popup-utils/valid-origin? "ftp://example.com/*"))
      (.toBeFalsy)))

(defn- test-rejects-empty-string []
  (-> (expect (popup-utils/valid-origin? ""))
      (.toBeFalsy)))

(defn- test-rejects-nil []
  (-> (expect (popup-utils/valid-origin? nil))
      (.toBeFalsy)))

(defn- test-rejects-whitespace-only-string []
  (-> (expect (popup-utils/valid-origin? "   "))
      (.toBeFalsy)))

(defn- test-trims-whitespace-before-validation []
  (-> (expect (popup-utils/valid-origin? "  https://example.com/*  "))
      (.toBe true)))

;; origin-already-exists? tests

(defn- test-returns-true-when-origin-is-in-default-list []
  (-> (expect (popup-utils/origin-already-exists?
               "https://github.com/"
               ["https://github.com/" "https://gitlab.com/"]
               []))
      (.toBe true)))

(defn- test-returns-true-when-origin-is-in-user-list []
  (-> (expect (popup-utils/origin-already-exists?
               "https://custom.com/"
               ["https://github.com/"]
               ["https://custom.com/"]))
      (.toBe true)))

(defn- test-returns-false-when-origin-is-in-neither-list []
  (-> (expect (popup-utils/origin-already-exists?
               "https://new.com/"
               ["https://github.com/"]
               ["https://custom.com/"]))
      (.toBeFalsy)))

(defn- test-trims-whitespace-before-comparison []
  (-> (expect (popup-utils/origin-already-exists?
               "  https://github.com/  "
               ["https://github.com/"]
               []))
      (.toBe true)))

;; sort-scripts-for-display tests

(def builtin? #(and (:script/id %) (.startsWith (:script/id %) "epupp-builtin-")))

(defn- test-sorts-user-scripts-alphabetically-by-name []
  (let [scripts [{:script/id "s1" :script/name "zebra.cljs"}
                 {:script/id "s2" :script/name "alpha.cljs"}
                 {:script/id "s3" :script/name "mike.cljs"}]
        result (vec (popup-utils/sort-scripts-for-display scripts builtin?))]
    (-> (expect (:script/name (first result)))
        (.toBe "alpha.cljs"))
    (-> (expect (:script/name (second result)))
        (.toBe "mike.cljs"))
    (-> (expect (:script/name (nth result 2)))
        (.toBe "zebra.cljs"))))

(defn- test-places-builtin-scripts-after-user-scripts []
  (let [scripts [{:script/id "epupp-builtin-gist" :script/name "Gist Installer"}
                 {:script/id "s1" :script/name "my_script.cljs"}]
        result (vec (popup-utils/sort-scripts-for-display scripts builtin?))]
    (-> (expect (:script/name (first result)))
        (.toBe "my_script.cljs"))
    (-> (expect (:script/name (second result)))
        (.toBe "Gist Installer"))))

(defn- test-sorts-builtin-scripts-alphabetically-among-themselves []
  (let [scripts [{:script/id "epupp-builtin-zzz" :script/name "Zzz Builtin"}
                 {:script/id "epupp-builtin-aaa" :script/name "Aaa Builtin"}
                 {:script/id "s1" :script/name "user.cljs"}]
        result (vec (popup-utils/sort-scripts-for-display scripts builtin?))]
    (-> (expect (:script/name (first result)))
        (.toBe "user.cljs"))
    (-> (expect (:script/name (second result)))
        (.toBe "Aaa Builtin"))
    (-> (expect (:script/name (nth result 2)))
        (.toBe "Zzz Builtin"))))

(defn- test-sorts-case-insensitively []
  (let [scripts [{:script/id "s1" :script/name "Zebra.cljs"}
                 {:script/id "s2" :script/name "alpha.cljs"}]
        result (vec (popup-utils/sort-scripts-for-display scripts builtin?))]
    (-> (expect (:script/name (first result)))
        (.toBe "alpha.cljs"))
    (-> (expect (:script/name (second result)))
        (.toBe "Zebra.cljs"))))

(defn- test-handles-empty-list []
  (let [result (vec (popup-utils/sort-scripts-for-display [] builtin?))]
    (-> (expect (count result))
        (.toBe 0))))

(defn- test-handles-list-with-only-builtins []
  (let [scripts [{:script/id "epupp-builtin-b" :script/name "Beta"}
                 {:script/id "epupp-builtin-a" :script/name "Alpha"}]
        result (vec (popup-utils/sort-scripts-for-display scripts builtin?))]
    (-> (expect (count result))
        (.toBe 2))
    (-> (expect (:script/name (first result)))
        (.toBe "Alpha"))))

;; ============================================================
;; Test Registration
;; ============================================================

(describe "status-class"
          (fn []
            (test "returns nil for nil status" test-returns-nil-for-nil-status)
            (test "returns failed class for Failed prefix" test-returns-failed-class-for-failed-prefix)
            (test "returns failed class for Error prefix" test-returns-failed-class-for-error-prefix)
            (test "returns pending class for status ending with ellipsis" test-returns-pending-class-for-status-ending-with-ellipsis)
            (test "returns pending class for not connected status" test-returns-pending-class-for-not-connected-status)
            (test "returns base status class for success status" test-returns-base-status-class-for-success-status)))

;; ============================================================
;; generate-server-cmd tests
;; ============================================================

(describe "generate-server-cmd"
          (fn []
            (test "generates command with custom ports" test-generates-command-with-custom-ports)
            (test "generates command with default ports" test-generates-command-with-default-ports)))

;; ============================================================
;; toggle-script-in-list tests
;; ============================================================

(describe "toggle-script-in-list"
          (fn []
            (test "enables a disabled script" test-enables-a-disabled-script)
            (test "disables an enabled script" test-disables-an-enabled-script)
            (test "does not modify other scripts" test-does-not-modify-other-scripts)))

;; ============================================================
;; remove-script-from-list tests
;; ============================================================

(describe "remove-script-from-list"
          (fn []
            (test "removes script by id" test-removes-script-by-id)
            (test "returns empty list when removing last script" test-returns-empty-list-when-removing-last-script)
            (test "returns unchanged list for non-existent id" test-returns-unchanged-list-for-non-existent-id)))

;; ============================================================
;; valid-origin? tests
;; ============================================================

(describe "valid-origin?"
          (fn []
            (test "accepts glob pattern with wildcard" test-accepts-glob-pattern-with-wildcard)
            (test "accepts http:// glob pattern" test-accepts-http-glob-pattern)
            (test "accepts complex glob pattern" test-accepts-complex-glob-pattern)
            (test "accepts complete URL with path" test-accepts-complete-url-with-path)
            (test "rejects URL without wildcard or path" test-rejects-url-without-wildcard-or-path)
            (test "rejects URL without trailing slash" test-rejects-url-without-trailing-slash)
            (test "rejects ftp:// protocol" test-rejects-ftp-protocol)
            (test "rejects empty string" test-rejects-empty-string)
            (test "rejects nil" test-rejects-nil)
            (test "rejects whitespace-only string" test-rejects-whitespace-only-string)
            (test "trims whitespace before validation" test-trims-whitespace-before-validation)))

;; ============================================================
;; origin-already-exists? tests
;; ============================================================

(describe "origin-already-exists?"
          (fn []
            (test "returns true when origin is in default list" test-returns-true-when-origin-is-in-default-list)
            (test "returns true when origin is in user list" test-returns-true-when-origin-is-in-user-list)
            (test "returns false when origin is in neither list" test-returns-false-when-origin-is-in-neither-list)
            (test "trims whitespace before comparison" test-trims-whitespace-before-comparison)))

;; ============================================================
;; sort-scripts-for-display tests
;; ============================================================

(describe "sort-scripts-for-display"
          (fn []
            (test "sorts user scripts alphabetically by name" test-sorts-user-scripts-alphabetically-by-name)
            (test "places built-in scripts after user scripts" test-places-builtin-scripts-after-user-scripts)
            (test "sorts built-in scripts alphabetically among themselves" test-sorts-builtin-scripts-alphabetically-among-themselves)
            (test "sorts case-insensitively" test-sorts-case-insensitively)
            (test "handles empty list" test-handles-empty-list)
            (test "handles list with only built-ins" test-handles-list-with-only-builtins)))
