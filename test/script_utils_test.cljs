(ns script-utils-test
  (:require ["vitest" :as vt]
            [script-utils :as script-utils]))

;; ============================================================
;; URL Pattern Matching Tests
;; ============================================================

(vt/describe "pattern->regex"
  (fn []
    (vt/test "handles <all_urls> pattern"
          (fn []
            (let [regex (script-utils/pattern->regex "<all_urls>")]
              (-> (vt/expect (.test regex "https://example.com/page"))
                  (.toBe true))
              (-> (vt/expect (.test regex "http://localhost:3000"))
                  (.toBe true)))))

    (vt/test "handles wildcard scheme *://"
          (fn []
            (let [regex (script-utils/pattern->regex "*://github.com/*")]
              (-> (vt/expect (.test regex "https://github.com/foo"))
                  (.toBe true))
              (-> (vt/expect (.test regex "http://github.com/bar"))
                  (.toBe true))
              ;; Note: *:// pattern matches any scheme - this is by design
              (-> (vt/expect (.test regex "ftp://github.com/baz"))
                  (.toBe true)))))

    (vt/test "handles wildcard in path"
          (fn []
            (let [regex (script-utils/pattern->regex "https://example.com/*")]
              (-> (vt/expect (.test regex "https://example.com/"))
                  (.toBe true))
              (-> (vt/expect (.test regex "https://example.com/foo/bar"))
                  (.toBe true)))))))

(vt/describe "url-matches-pattern?"
             (fn []
               (vt/test "matches GitHub URLs"
                        (fn []
                          (-> (vt/expect (script-utils/url-matches-pattern? "https://github.com/user/repo" "*://github.com/*"))
                              (.toBe true))
                          (-> (vt/expect (script-utils/url-matches-pattern? "https://gitlab.com/user/repo" "*://github.com/*"))
                              (.toBe false))))

               (vt/test "matches subdomain wildcards"
                        (fn []
                          (-> (vt/expect (script-utils/url-matches-pattern? "https://docs.example.com/page" "https://*.example.com/*"))
                              (.toBe true))
                          (-> (vt/expect (script-utils/url-matches-pattern? "https://api.example.com/v1" "https://*.example.com/*"))
                              (.toBe true))))

               (vt/test "matches <all_urls>"
                        (fn []
                          (-> (vt/expect (script-utils/url-matches-pattern? "https://anything.com/whatever" "<all_urls>"))
                              (.toBe true))))

               (vt/test "handles URLs with query parameters"
                        (fn []
                          (-> (vt/expect (script-utils/url-matches-pattern? "https://example.com/page?query=1&foo=bar" "*://example.com/*"))
                              (.toBe true))))

               (vt/test "properly escapes dots in domain - prevents false matches"
                        (fn []
                          ;; github.com pattern should NOT match githubXcom
                          (-> (vt/expect (script-utils/url-matches-pattern? "https://githubXcom/foo" "*://github.com/*"))
                              (.toBe false))))

               (vt/test "handles URL fragments"
                        (fn []
                          (-> (vt/expect (script-utils/url-matches-pattern? "https://example.com/page#section" "*://example.com/*"))
                              (.toBe true))))))

(vt/describe "url-matches-any-pattern?"
  (fn []
    (vt/test "returns true when any pattern matches"
          (fn []
            (-> (vt/expect (script-utils/url-matches-any-pattern?
                         "https://github.com/foo"
                         ["*://gitlab.com/*" "*://github.com/*"]))
                (.toBe true))))

    (vt/test "returns false/nil when no pattern matches"
          (fn []
            (-> (vt/expect (script-utils/url-matches-any-pattern?
                         "https://example.com/foo"
                         ["*://gitlab.com/*" "*://github.com/*"]))
                (.toBeFalsy))))))

;; ============================================================
;; Script Query Function Tests
;; ============================================================

(vt/describe "get-matching-pattern"
  (fn []
    (vt/test "returns matching pattern from script"
          (fn []
            (let [script {:script/id "test"
                          :script/match ["*://github.com/*" "*://gitlab.com/*"]}]
              (-> (vt/expect (script-utils/get-matching-pattern "https://github.com/foo" script))
                  (.toBe "*://github.com/*")))))

    (vt/test "returns nil for non-matching URL"
          (fn []
            (let [script {:script/id "test"
                          :script/match ["*://github.com/*"]}]
              ;; Clojure nil becomes JS undefined
              (-> (vt/expect (script-utils/get-matching-pattern "https://example.com/foo" script))
                  (.toBeUndefined)))))

    (vt/test "returns nil for nil URL"
          (fn []
            (let [script {:script/id "test"
                          :script/match ["*://github.com/*"]}]
              ;; Clojure nil becomes JS undefined
              (-> (vt/expect (script-utils/get-matching-pattern nil script))
                  (.toBeUndefined)))))))

(vt/describe "pattern-approved?"
  (fn []
    (vt/test "returns true when pattern is approved"
          (fn []
            (let [script {:script/id "test"
                          :script/approved-patterns ["*://github.com/*"]}]
              (-> (vt/expect (script-utils/pattern-approved? script "*://github.com/*"))
                  (.toBeTruthy)))))

    (vt/test "returns false when pattern is not approved"
          (fn []
            (let [script {:script/id "test"
                          :script/approved-patterns ["*://gitlab.com/*"]}]
              (-> (vt/expect (script-utils/pattern-approved? script "*://github.com/*"))
                  (.toBeFalsy)))))))

(vt/describe "get-required-origins"
  (fn []
    (vt/test "extracts unique patterns from multiple scripts"
          (fn []
            (let [scripts [{:script/match ["*://github.com/*" "*://gitlab.com/*"]}
                           {:script/match ["*://github.com/*" "*://example.com/*"]}]
                  origins (script-utils/get-required-origins scripts)]
              (-> (vt/expect (count origins))
                  (.toBe 3))
              (-> (vt/expect (some #(= % "*://github.com/*") origins))
                  (.toBeTruthy))
              (-> (vt/expect (some #(= % "*://gitlab.com/*") origins))
                  (.toBeTruthy))
              (-> (vt/expect (some #(= % "*://example.com/*") origins))
                  (.toBeTruthy)))))))

;; ============================================================
;; Script Data Transformation Tests
;; ============================================================

(vt/describe "parse-scripts"
  (fn []
    (vt/test "converts JS script object to Clojure map with namespaced keys"
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
              (-> (vt/expect (count result))
                  (.toBe 1))
              (-> (vt/expect (:script/id script))
                  (.toBe "test-1"))
              (-> (vt/expect (:script/name script))
                  (.toBe "Test Script"))
              (-> (vt/expect (:script/code script))
                  (.toBe "(println \"hello\")"))
              (-> (vt/expect (:script/enabled script))
                  (.toBe true))
              (-> (vt/expect (first (:script/match script)))
                  (.toBe "*://github.com/*"))
              (-> (vt/expect (first (:script/approved-patterns script)))
                  (.toBe "*://github.com/*")))))

    (vt/test "handles nil/undefined input"
          (fn []
            (-> (vt/expect (script-utils/parse-scripts nil))
                (.toEqual []))
            (-> (vt/expect (script-utils/parse-scripts js/undefined))
                (.toEqual []))))

    (vt/test "handles empty array"
          (fn []
            (-> (vt/expect (script-utils/parse-scripts #js []))
                (.toEqual []))))

    (vt/test "handles missing optional fields"
          (fn []
            (let [js-script #js {:id "minimal"
                                 :name "Minimal"
                                 :match #js []
                                 :code ""}
                  result (script-utils/parse-scripts #js [js-script])
                  script (first result)]
              (-> (vt/expect (:script/id script))
                  (.toBe "minimal"))
              ;; Missing fields become nil/undefined
              (-> (vt/expect (:script/approved-patterns script))
                  (.toEqual [])))))))

(vt/describe "script->js"
  (fn []
    (vt/test "converts Clojure script map to JS object"
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
              (-> (vt/expect (.-id result))
                  (.toBe "test-1"))
              (-> (vt/expect (.-name result))
                  (.toBe "Test Script"))
              (-> (vt/expect (.-code result))
                  (.toBe "(println \"hello\")"))
              (-> (vt/expect (.-enabled result))
                  (.toBe true))
              (-> (vt/expect (.-created result))
                  (.toBe "2024-01-01"))
              (-> (vt/expect (.-modified result))
                  (.toBe "2024-01-02"))
              ;; Arrays
              (-> (vt/expect (aget (.-match result) 0))
                  (.toBe "*://github.com/*"))
              (-> (vt/expect (aget (.-match result) 1))
                  (.toBe "*://gitlab.com/*"))
              (-> (vt/expect (aget (.-approvedPatterns result) 0))
                  (.toBe "*://github.com/*")))))

    (vt/test "handles nil values in script"
          (fn []
            (let [script {:script/id "test"
                          :script/name nil
                          :script/match nil
                          :script/code nil}
                  result (script-utils/script->js script)]
              (-> (vt/expect (.-id result))
                  (.toBe "test"))
              (-> (vt/expect (.-name result))
                  (.toBeNull)))))))
