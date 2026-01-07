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
                        (.toBe true))))))

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

(describe "pattern-approved?"
          (fn []
            (test "returns true when pattern is approved"
                  (fn []
                    (let [script {:script/id "test"
                                  :script/approved-patterns ["*://github.com/*"]}]
                      (-> (expect (script-utils/pattern-approved? script "*://github.com/*"))
                          (.toBeTruthy)))))

            (test "returns false when pattern is not approved"
                  (fn []
                    (let [script {:script/id "test"
                                  :script/approved-patterns ["*://gitlab.com/*"]}]
                      (-> (expect (script-utils/pattern-approved? script "*://github.com/*"))
                          (.toBeFalsy)))))))

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
                          (.toEqual [])))))))

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
                    (-> (expect (script-utils/builtin-script-id? "scittle-tamper-builtin-gist-installer"))
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
                    (let [script {:script/id "scittle-tamper-builtin-gist-installer"
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
