(ns script-utils-test
  (:require ["vitest" :refer [describe test expect]]
            [script-utils :as script-utils]))

;; ============================================================
;; URL Pattern Matching Tests
;; ============================================================

(describe "pattern->regex"
          (fn []
            (test "handles <all_urls> pattern"
                  (fn []
                    (let [regex (script-utils/pattern->regex "<all_urls>")]
                      (-> (expect (.test regex "https://example.com/page"))
                          (.toBe true))
                      (-> (expect (.test regex "http://localhost:3000"))
                          (.toBe true)))))

            (test "handles wildcard scheme *://"
                  (fn []
                    (let [regex (script-utils/pattern->regex "*://github.com/*")]
                      (-> (expect (.test regex "https://github.com/foo"))
                          (.toBe true))
                      (-> (expect (.test regex "http://github.com/bar"))
                          (.toBe true))
              ;; Note: *:// pattern matches any scheme - this is by design
                      (-> (expect (.test regex "ftp://github.com/baz"))
                          (.toBe true)))))

            (test "handles wildcard in path"
                  (fn []
                    (let [regex (script-utils/pattern->regex "https://example.com/*")]
                      (-> (expect (.test regex "https://example.com/"))
                          (.toBe true))
                      (-> (expect (.test regex "https://example.com/foo/bar"))
                          (.toBe true)))))))

(describe "url-matches-pattern?"
          (fn []
            (test "matches GitHub URLs"
                  (fn []
                    (-> (expect (script-utils/url-matches-pattern? "https://github.com/user/repo" "*://github.com/*"))
                        (.toBe true))
                    (-> (expect (script-utils/url-matches-pattern? "https://gitlab.com/user/repo" "*://github.com/*"))
                        (.toBe false))))

            (test "matches subdomain wildcards"
                  (fn []
                    (-> (expect (script-utils/url-matches-pattern? "https://docs.example.com/page" "https://*.example.com/*"))
                        (.toBe true))
                    (-> (expect (script-utils/url-matches-pattern? "https://api.example.com/v1" "https://*.example.com/*"))
                        (.toBe true))))

            (test "matches <all_urls>"
                  (fn []
                    (-> (expect (script-utils/url-matches-pattern? "https://anything.com/whatever" "<all_urls>"))
                        (.toBe true))))

            (test "handles URLs with query parameters"
                  (fn []
                    (-> (expect (script-utils/url-matches-pattern? "https://example.com/page?query=1&foo=bar" "*://example.com/*"))
                        (.toBe true))))

            (test "properly escapes dots in domain - prevents false matches"
                  (fn []
                          ;; github.com pattern should NOT match githubXcom
                    (-> (expect (script-utils/url-matches-pattern? "https://githubXcom/foo" "*://github.com/*"))
                        (.toBe false))))

            (test "handles URL fragments"
                  (fn []
                    (-> (expect (script-utils/url-matches-pattern? "https://example.com/page#section" "*://example.com/*"))
                        (.toBe true))))

            (test "returns false for non-string patterns (corrupted data)"
                  (fn []
                    ;; Nested array from corrupted storage
                    (-> (expect (script-utils/url-matches-pattern? "https://github.com/foo" #js ["*://github.com/*"]))
                        (.toBe false))
                    ;; nil pattern
                    (-> (expect (script-utils/url-matches-pattern? "https://github.com/foo" nil))
                        (.toBe false))
                    ;; number pattern
                    (-> (expect (script-utils/url-matches-pattern? "https://github.com/foo" 123))
                        (.toBe false))))))

(describe "url-matches-any-pattern?"
          (fn []
            (test "returns true when any pattern matches"
                  (fn []
                    (-> (expect (script-utils/url-matches-any-pattern?
                                 "https://github.com/foo"
                                 ["*://gitlab.com/*" "*://github.com/*"]))
                        (.toBe true))))

            (test "returns false/nil when no pattern matches"
                  (fn []
                    (-> (expect (script-utils/url-matches-any-pattern?
                                 "https://example.com/foo"
                                 ["*://gitlab.com/*" "*://github.com/*"]))
                        (.toBeFalsy))))))

;; ============================================================
;; Script Query Function Tests
;; ============================================================

(describe "get-matching-pattern"
          (fn []
            (test "returns matching pattern from script"
                  (fn []
                    (let [script {:script/id "test"
                                  :script/match ["*://github.com/*" "*://gitlab.com/*"]}]
                      (-> (expect (script-utils/get-matching-pattern "https://github.com/foo" script))
                          (.toBe "*://github.com/*")))))

            (test "returns nil for non-matching URL"
                  (fn []
                    (let [script {:script/id "test"
                                  :script/match ["*://github.com/*"]}]
              ;; Clojure nil becomes JS undefined
                      (-> (expect (script-utils/get-matching-pattern "https://example.com/foo" script))
                          (.toBeUndefined)))))

            (test "returns nil for nil URL"
                  (fn []
                    (let [script {:script/id "test"
                                  :script/match ["*://github.com/*"]}]
              ;; Clojure nil becomes JS undefined
                      (-> (expect (script-utils/get-matching-pattern nil script))
                          (.toBeUndefined)))))))

(describe "get-required-origins"
          (fn []
            (test "extracts unique patterns from multiple scripts"
                  (fn []
                    (let [scripts [{:script/match ["*://github.com/*" "*://gitlab.com/*"]}
                                   {:script/match ["*://github.com/*" "*://example.com/*"]}]
                          origins (script-utils/get-required-origins scripts)]
                      (-> (expect (count origins))
                          (.toBe 3))
                      (-> (expect (some #(= % "*://github.com/*") origins))
                          (.toBeTruthy))
                      (-> (expect (some #(= % "*://gitlab.com/*") origins))
                          (.toBeTruthy))
                      (-> (expect (some #(= % "*://example.com/*") origins))
                          (.toBeTruthy)))))))

;; ============================================================
;; Script Data Transformation Tests
;; ============================================================

(describe "parse-scripts"
          (fn []
            (test "converts JS script object to Clojure map with namespaced keys"
                  (fn []
                    (let [js-script #js {:id "test-1"
                                         :name "Test Script"
                                         :match #js ["*://github.com/*"]
                                         :code "(println \"hello\")"
                                         :enabled true
                                         :created "2024-01-01"
                                         :modified "2024-01-02"
                                         :approvedPatterns #js ["*://github.com/*"]}
                          result (script-utils/parse-scripts #js [js-script])
                          script (first result)]
                      (-> (expect (count result))
                          (.toBe 1))
                      (-> (expect (:script/id script))
                          (.toBe "test-1"))
                      (-> (expect (:script/name script))
                          (.toBe "Test Script"))
                      (-> (expect (:script/code script))
                          (.toBe "(println \"hello\")"))
                      (-> (expect (:script/enabled script))
                          (.toBe true))
                      (-> (expect (first (:script/match script)))
                          (.toBe "*://github.com/*"))
                      (-> (expect (first (:script/approved-patterns script)))
                          (.toBe "*://github.com/*")))))

            (test "parses description field"
                  (fn []
                    (let [js-script #js {:id "test-desc"
                                         :name "Described Script"
                                         :description "A helpful description of what this does"
                                         :match #js ["*://example.com/*"]
                                         :code "(println \"hi\")"}
                          result (script-utils/parse-scripts #js [js-script])
                          script (first result)]
                      (-> (expect (:script/description script))
                          (.toBe "A helpful description of what this does")))))

            (test "handles missing description"
                  (fn []
                    (let [js-script #js {:id "no-desc"
                                         :name "No Description"
                                         :match #js ["*://example.com/*"]
                                         :code "(println \"hi\")"}
                          result (script-utils/parse-scripts #js [js-script])
                          script (first result)]
                      ;; Missing description becomes undefined in Squint
                      (-> (expect (:script/description script))
                          (.toBeUndefined)))))

            (test "handles nil/undefined input"
                  (fn []
                    (-> (expect (script-utils/parse-scripts nil))
                        (.toEqual []))
                    (-> (expect (script-utils/parse-scripts js/undefined))
                        (.toEqual []))))

            (test "handles empty array"
                  (fn []
                    (-> (expect (script-utils/parse-scripts #js []))
                        (.toEqual []))))

            (test "handles missing optional fields"
                  (fn []
                    (let [js-script #js {:id "minimal"
                                         :name "Minimal"
                                         :match #js []
                                         :code ""}
                          result (script-utils/parse-scripts #js [js-script])
                          script (first result)]
                      (-> (expect (:script/id script))
                          (.toBe "minimal"))
              ;; Missing fields become nil/undefined
                      (-> (expect (:script/approved-patterns script))
                          (.toEqual []))))))

          (test "preserves normal flat match arrays"
                (fn []
                  (let [js-script #js {:id "normal"
                                       :name "Normal"
                                       :match #js ["*://github.com/*" "*://gitlab.com/*"]
                                       :code ""}
                        result (script-utils/parse-scripts #js [js-script])
                        script (first result)]
                    (-> (expect (:script/match script))
                        (.toEqual ["*://github.com/*" "*://gitlab.com/*"]))))))

(describe "script->js"
          (fn []
            (test "converts Clojure script map to JS object"
                  (fn []
                    (let [script {:script/id "test-1"
                                  :script/name "Test Script"
                                  :script/match ["*://github.com/*" "*://gitlab.com/*"]
                                  :script/code "(println \"hello\")"
                                  :script/enabled true
                                  :script/created "2024-01-01"
                                  :script/modified "2024-01-02"
                                  :script/approved-patterns ["*://github.com/*"]}
                          result (script-utils/script->js script)]
                      (-> (expect (.-id result))
                          (.toBe "test-1"))
                      (-> (expect (.-name result))
                          (.toBe "Test Script"))
                      (-> (expect (.-code result))
                          (.toBe "(println \"hello\")"))
                      (-> (expect (.-enabled result))
                          (.toBe true))
                      (-> (expect (.-created result))
                          (.toBe "2024-01-01"))
                      (-> (expect (.-modified result))
                          (.toBe "2024-01-02"))
              ;; Arrays
                      (-> (expect (aget (.-match result) 0))
                          (.toBe "*://github.com/*"))
                      (-> (expect (aget (.-match result) 1))
                          (.toBe "*://gitlab.com/*"))
                      (-> (expect (aget (.-approvedPatterns result) 0))
                          (.toBe "*://github.com/*")))))

            (test "handles nil values in script"
                  (fn []
                    (let [script {:script/id "test"
                                  :script/name nil
                                  :script/match nil
                                  :script/code nil}
                          result (script-utils/script->js script)]
                      (-> (expect (.-id result))
                          (.toBe "test"))
                      (-> (expect (.-name result))
                          (.toBeNull)))))

            (test "includes description field"
                  (fn []
                    (let [script {:script/id "test-desc"
                                  :script/name "Described Script"
                                  :script/description "A helpful description"
                                  :script/match ["*://example.com/*"]
                                  :script/code "(println \"hi\")"}
                          result (script-utils/script->js script)]
                      (-> (expect (.-description result))
                          (.toBe "A helpful description")))))

            (test "handles nil description"
                  (fn []
                    (let [script {:script/id "test"
                                  :script/name "No Desc"
                                  :script/description nil
                                  :script/match ["*://example.com/*"]
                                  :script/code "(println \"hi\")"}
                          result (script-utils/script->js script)]
                      (-> (expect (.-description result))
                          (.toBeNull)))))))



;; ============================================================
;; Built-in Script Detection Tests
;; ============================================================

(describe "builtin-script-id?"
          (fn []
            (test "returns truthy for builtin prefix"
                  (fn []
                    (-> (expect (script-utils/builtin-script-id? "epupp-builtin-gist-installer"))
                        (.toBeTruthy))))

            (test "returns falsy for user script ids"
                  (fn []
                    (-> (expect (script-utils/builtin-script-id? "my-custom-script"))
                        (.toBeFalsy))
                    (-> (expect (script-utils/builtin-script-id? "user-script-123"))
                        (.toBeFalsy))))

            (test "returns falsy for nil"
                  (fn []
                    (-> (expect (script-utils/builtin-script-id? nil))
                        (.toBeFalsy))))

            (test "returns falsy for empty string"
                  (fn []
                    (-> (expect (script-utils/builtin-script-id? ""))
                        (.toBeFalsy))))))

(describe "builtin-script?"
          (fn []
            (test "returns truthy for script with builtin prefix"
                  (fn []
                    (let [script {:script/id "epupp-builtin-gist-installer"
                                  :script/name "Gist Installer"}]
                      (-> (expect (script-utils/builtin-script? script))
                          (.toBeTruthy)))))

            (test "returns falsy for user scripts"
                  (fn []
                    (let [script {:script/id "my-user-script"
                                  :script/name "My Script"}]
                      (-> (expect (script-utils/builtin-script? script))
                          (.toBeFalsy)))))

            (test "returns falsy for script with nil id"
                  (fn []
                    (let [script {:script/id nil
                                  :script/name "No ID Script"}]
                      (-> (expect (script-utils/builtin-script? script))
                          (.toBeFalsy)))))

            (test "returns falsy for script without id key"
                  (fn []
                    (let [script {:script/name "No ID Key"}]
                      (-> (expect (script-utils/builtin-script? script))
                          (.toBeFalsy)))))))

(describe "filter-visible-scripts"
          (fn []
            (test "excludes built-ins by default"
                  (fn []
                    (let [scripts [{:script/id "epupp-builtin-gist-installer"
                                    :script/name "Builtin"}
                                   {:script/id "script-1"
                                    :script/name "User"}]
                          result (script-utils/filter-visible-scripts scripts false)]
                      (-> (expect (count result)) (.toBe 1))
                      (-> (expect (:script/id (first result))) (.toBe "script-1")))))

            (test "includes built-ins when include-hidden? is true"
                  (fn []
                    (let [scripts [{:script/id "epupp-builtin-gist-installer"
                                    :script/name "Builtin"}
                                   {:script/id "script-1"
                                    :script/name "User"}]
                          result (script-utils/filter-visible-scripts scripts true)]
                      (-> (expect (count result)) (.toBe 2))
                      (-> (expect (some #(= "epupp-builtin-gist-installer" (:script/id %)) result))
                          (.toBeTruthy)))))))

;; ============================================================
;; Script Name Normalization Tests
;; ============================================================

(describe "normalize-script-name"
          (fn []
            (test "lowercases name"
                  (fn []
                    (-> (expect (script-utils/normalize-script-name "MyScript"))
                        (.toBe "myscript.cljs"))))

            (test "replaces spaces with underscores"
                  (fn []
                    (-> (expect (script-utils/normalize-script-name "my script"))
                        (.toBe "my_script.cljs"))))

            (test "replaces dashes with underscores"
                  (fn []
                    (-> (expect (script-utils/normalize-script-name "my-script"))
                        (.toBe "my_script.cljs"))))

            (test "replaces dots with underscores except .cljs extension"
                  (fn []
                    (-> (expect (script-utils/normalize-script-name "my.script.name"))
                        (.toBe "my_script_name.cljs"))
                    (-> (expect (script-utils/normalize-script-name "v1.0.0"))
                        (.toBe "v1_0_0.cljs"))
                    (-> (expect (script-utils/normalize-script-name "my.script.cljs"))
                        (.toBe "my_script.cljs"))))

            (test "preserves slashes for namespace paths"
                  (fn []
                    (-> (expect (script-utils/normalize-script-name "my-project/utils"))
                        (.toBe "my_project/utils.cljs"))))

            (test "appends .cljs if missing"
                  (fn []
                    (-> (expect (script-utils/normalize-script-name "script"))
                        (.toBe "script.cljs"))))

            (test "preserves .cljs if present"
                  (fn []
                    (-> (expect (script-utils/normalize-script-name "script.cljs"))
                        (.toBe "script.cljs"))))

            (test "removes invalid characters"
                  (fn []
                    (-> (expect (script-utils/normalize-script-name "my@script!"))
                        (.toBe "myscript.cljs"))))

            (test "handles empty string"
                  (fn []
                    (-> (expect (script-utils/normalize-script-name ""))
                        (.toBe ".cljs"))))))

;; ============================================================
;; Run-at Timing Tests
;; ============================================================

(describe "normalize-run-at"
          (fn []
            (test "returns valid run-at values unchanged"
                  (fn []
                    (-> (expect (script-utils/normalize-run-at "document-start"))
                        (.toBe "document-start"))
                    (-> (expect (script-utils/normalize-run-at "document-end"))
                        (.toBe "document-end"))
                    (-> (expect (script-utils/normalize-run-at "document-idle"))
                        (.toBe "document-idle"))))

            (test "returns default for nil"
                  (fn []
                    (-> (expect (script-utils/normalize-run-at nil))
                        (.toBe "document-idle"))))

            (test "returns default for undefined"
                  (fn []
                    (-> (expect (script-utils/normalize-run-at js/undefined))
                        (.toBe "document-idle"))))

            (test "returns default for invalid values"
                  (fn []
                    (-> (expect (script-utils/normalize-run-at "invalid"))
                        (.toBe "document-idle"))
                    (-> (expect (script-utils/normalize-run-at ""))
                        (.toBe "document-idle"))
                    (-> (expect (script-utils/normalize-run-at "DOCUMENT-START"))
                        (.toBe "document-idle"))))))

(describe "parse-scripts with run-at"
          (fn []
            (test "parses runAt field"
                  (fn []
                    (let [js-script #js {:id "test-run-at"
                                         :name "Test"
                                         :match #js []
                                         :code ""
                                         :runAt "document-start"}
                          result (script-utils/parse-scripts #js [js-script])
                          script (first result)]
                      (-> (expect (:script/run-at script))
                          (.toBe "document-start")))))

            (test "defaults to document-idle when runAt missing"
                  (fn []
                    (let [js-script #js {:id "test-no-run-at"
                                         :name "Test"
                                         :match #js []
                                         :code ""}
                          result (script-utils/parse-scripts #js [js-script])
                          script (first result)]
                      (-> (expect (:script/run-at script))
                          (.toBe "document-idle")))))

            (test "defaults to document-idle for invalid runAt"
                  (fn []
                    (let [js-script #js {:id "test-invalid-run-at"
                                         :name "Test"
                                         :match #js []
                                         :code ""
                                         :runAt "invalid-value"}
                          result (script-utils/parse-scripts #js [js-script])
                          script (first result)]
                      (-> (expect (:script/run-at script))
                          (.toBe "document-idle")))))))

(describe "script->js with run-at"
          (fn []
            (test "serializes run-at as runAt"
                  (fn []
                    (let [script {:script/id "test"
                                  :script/name "Test"
                                  :script/match []
                                  :script/code ""
                                  :script/run-at "document-start"}
                          result (script-utils/script->js script)]
                      (-> (expect (.-runAt result))
                          (.toBe "document-start")))))

            (test "handles nil run-at"
                  (fn []
                    (let [script {:script/id "test"
                                  :script/name "Test"
                                  :script/match []
                                  :script/code ""
                                  :script/run-at nil}
                          result (script-utils/script->js script)]
                      (-> (expect (.-runAt result))
                          (.toBeNull)))))

            (test "round-trips run-at through parse-scripts and script->js"
                  (fn []
                    (let [original {:script/id "round-trip"
                                    :script/name "Round Trip"
                                    :script/match ["*://example.com/*"]
                                    :script/code "(println \"hi\")"
                                    :script/enabled true
                                    :script/run-at "document-end"
                                    :script/approved-patterns []}
                          js-obj (script-utils/script->js original)
                          parsed (first (script-utils/parse-scripts #js [js-obj]))]
                      (-> (expect (:script/run-at parsed))
                          (.toBe "document-end")))))))

(describe "url-to-match-pattern"
          (fn []
            (test "converts URL to match pattern with specific scheme"
                  (fn []
                    (let [result (script-utils/url-to-match-pattern "https://github.com/foo/bar")]
                      (-> (expect result)
                          (.toBe "https://github.com/*")))))

            (test "converts URL to match pattern with wildcard scheme"
                  (fn []
                    (let [result (script-utils/url-to-match-pattern
                                  "https://github.com/foo/bar"
                                  {:wildcard-scheme? true})]
                      (-> (expect result)
                          (.toBe "*://github.com/*")))))

            (test "handles http URLs"
                  (fn []
                    (let [result (script-utils/url-to-match-pattern "http://localhost:3000/page")]
                      (-> (expect result)
                          (.toBe "http://localhost/*")))))

            (test "handles URLs with paths and query params"
                  (fn []
                    (let [result (script-utils/url-to-match-pattern
                                  "https://example.com/path/to/page?query=1")]
                      (-> (expect result)
                          (.toBe "https://example.com/*")))))

            (test "returns nil for invalid URL"
                  (fn []
                    (let [result (script-utils/url-to-match-pattern "not-a-valid-url")]
                      (-> (expect result)
                          (.toBeNull)))))

            (test "returns nil for nil input"
                  (fn []
                    (let [result (script-utils/url-to-match-pattern nil)]
                      (-> (expect result)
                          (.toBeNull)))))

            (test "returns nil for empty string"
                  (fn []
                    (let [result (script-utils/url-to-match-pattern "")]
                      (-> (expect result)
                          (.toBeNull)))))))

(describe "parse-scripts with require"
          (fn []
            (test "parses require field as vector"
                  (fn []
                    (let [js-script #js {:id "test"
                                         :name "Test"
                                         :match #js ["*://example.com/*"]
                                         :code "()"
                                         :enabled true
                                         :require #js ["scittle://reagent.js" "scittle://pprint.js"]}
                          result (script-utils/parse-scripts #js [js-script])
                          script (first result)]
                      (-> (expect (:script/require script))
                          (.toEqual ["scittle://reagent.js" "scittle://pprint.js"])))))

            (test "defaults to empty vector when require missing"
                  (fn []
                    (let [js-script #js {:id "test"
                                         :name "Test"
                                         :match #js ["*://example.com/*"]
                                         :code "()"
                                         :enabled true}
                          result (script-utils/parse-scripts #js [js-script])
                          script (first result)]
                      (-> (expect (:script/require script))
                          (.toEqual [])))))))

(describe "script->js with require"
          (fn []
            (test "serializes require field"
                  (fn []
                    (let [script {:script/id "test"
                                  :script/name "Test"
                                  :script/match ["*://example.com/*"]
                                  :script/code "()"
                                  :script/enabled true
                                  :script/require ["scittle://reagent.js"]}
                          result (script-utils/script->js script)]
                      (-> (expect (.-require result))
                          (.toEqual #js ["scittle://reagent.js"])))))

            (test "handles nil require"
                  (fn []
                    (let [script {:script/id "test"
                                  :script/name "Test"
                                  :script/match ["*://example.com/*"]
                                  :script/code "()"
                                  :script/enabled true}
                          result (script-utils/script->js script)]
                      ;; Squint: nil becomes undefined (not null)
                      (-> (expect (.-require result))
                          (.toBeFalsy)))))

            (test "round-trips require through parse-scripts and script->js"
                  (fn []
                    (let [original {:script/id "round-trip"
                                    :script/name "Round Trip"
                                    :script/match ["*://example.com/*"]
                                    :script/code "()"
                                    :script/enabled true
                                    :script/require ["scittle://pprint.js" "scittle://reagent.js"]}
                          js-obj (script-utils/script->js original)
                          parsed (first (script-utils/parse-scripts #js [js-obj]))]
                      (-> (expect (:script/require parsed))
                          (.toEqual ["scittle://pprint.js" "scittle://reagent.js"])))))))
