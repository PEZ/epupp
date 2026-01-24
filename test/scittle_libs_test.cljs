(ns scittle-libs-test
  (:require ["vitest" :refer [describe test expect]]
            [scittle-libs :as libs]))

(describe "scittle-libs"
          (fn []
            (describe "scittle-url?"
                      (fn []
                        (test "returns true for scittle:// URLs"
                              (fn []
                                (-> (expect (libs/scittle-url? "scittle://pprint.js"))
                                    (.toBe true))
                                (-> (expect (libs/scittle-url? "scittle://reagent.js"))
                                    (.toBe true))))

                        (test "returns false for other URLs"
                              (fn []
                                (-> (expect (libs/scittle-url? "https://example.com/lib.js"))
                                    (.toBe false))
                                (-> (expect (libs/scittle-url? "http://localhost/test.js"))
                                    (.toBe false))))

                        (test "returns false for nil and non-strings"
                              (fn []
                                (-> (expect (libs/scittle-url? nil))
                                    (.toBe false))
                                (-> (expect (libs/scittle-url? 123))
                                    (.toBe false))))))

            (describe "resolve-scittle-url"
                      (fn []
                        (test "resolves valid scittle:// URLs to library key"
                              (fn []
                                (-> (expect (libs/resolve-scittle-url "scittle://pprint.js"))
                                    (.toBe "scittle/pprint"))
                                (-> (expect (libs/resolve-scittle-url "scittle://reagent.js"))
                                    (.toBe "scittle/reagent"))
                                (-> (expect (libs/resolve-scittle-url "scittle://js-interop.js"))
                                    (.toBe "scittle/js-interop"))))

                        (test "returns nil for unknown library"
                              (fn []
                                (-> (expect (libs/resolve-scittle-url "scittle://unknown.js"))
                                    (.toBeFalsy))))

                        (test "returns nil for non-scittle URLs"
                              (fn []
                                (-> (expect (libs/resolve-scittle-url "https://example.com/lib.js"))
                                    (.toBeFalsy))))

                        (test "returns nil for internal libraries"
                              (fn []
                                (-> (expect (libs/resolve-scittle-url "scittle://core.js"))
                                    (.toBeFalsy))
                                (-> (expect (libs/resolve-scittle-url "scittle://react.js"))
                                    (.toBeFalsy))))

                        (test "returns nil for nil input"
                              (fn []
                                (-> (expect (libs/resolve-scittle-url nil))
                                    (.toBeFalsy))))))

            (describe "get-library-files"
                      (fn []
                        (test "returns single file for standard library"
                              (fn []
                                (-> (expect (libs/get-library-files :scittle/pprint))
                                    (.toEqual ["scittle.pprint.js"]))))

                        (test "returns multiple files for react"
                              (fn []
                                (-> (expect (libs/get-library-files :scittle/react))
                                    (.toEqual ["react.production.min.js" "react-dom.production.min.js"]))))

                        (test "returns nil for unknown library"
                              (fn []
                                (-> (expect (libs/get-library-files :scittle/unknown))
                                    (.toBeFalsy))))))

            (describe "resolve-dependencies"
                      (fn []
                        (test "returns single library for library with only core dep"
                              (fn []
                                (-> (expect (libs/resolve-dependencies :scittle/pprint))
                                    (.toEqual ["scittle/pprint"]))))

                        (test "returns library for reagent (React is internal)"
                              (fn []
                                (-> (expect (libs/resolve-dependencies :scittle/reagent))
                                    (.toEqual ["scittle/reagent"]))))

                        (test "returns transitive deps for re-frame"
                              (fn []
                                (-> (expect (libs/resolve-dependencies :scittle/re-frame))
                                    (.toEqual ["scittle/reagent" "scittle/re-frame"]))))))

            (describe "expand-inject"
                      (fn []
                        (test "expands simple library without React"
                              (fn []
                                (let [result (libs/expand-inject "scittle://pprint.js")]
                                  (-> (expect (:inject/lib result))
                                      (.toBe "scittle/pprint"))
                                  (-> (expect (:inject/files result))
                                      (.toEqual ["scittle.pprint.js"])))))

                        (test "expands library with React dependency"
                              (fn []
                                (let [result (libs/expand-inject "scittle://reagent.js")]
                                  (-> (expect (:inject/lib result))
                                      (.toBe "scittle/reagent"))
                                  ;; Should include React files first
                                  (-> (expect (:inject/files result))
                                      (.toEqual ["react.production.min.js"
                                                 "react-dom.production.min.js"
                                                 "scittle.reagent.js"])))))

                        (test "expands library with transitive dependency"
                              (fn []
                                (let [result (libs/expand-inject "scittle://re-frame.js")]
                                  (-> (expect (:inject/lib result))
                                      (.toBe "scittle/re-frame"))
                                  ;; Should include React, then Reagent, then Re-frame
                                  (-> (expect (:inject/files result))
                                      (.toEqual ["react.production.min.js"
                                                 "react-dom.production.min.js"
                                                 "scittle.reagent.js"
                                                 "scittle.re-frame.js"])))))

                        (test "returns nil for internal library"
                              (fn []
                                (-> (expect (libs/expand-inject "scittle://core.js"))
                                    (.toBeFalsy))))

                        (test "returns nil for unknown library"
                              (fn []
                                (-> (expect (libs/expand-inject "scittle://unknown.js"))
                                    (.toBeFalsy))))

                        (test "returns nil for non-scittle URL"
                              (fn []
                                (-> (expect (libs/expand-inject "https://example.com/lib.js"))
                                    (.toBeFalsy))))))

            (describe "available-libraries"
                      (fn []
                        (test "returns sorted list of available libraries"
                              (fn []
                                (-> (expect (libs/available-libraries))
                                    (.toEqual ["scittle/cljs-ajax" "scittle/js-interop" "scittle/pprint"
                                               "scittle/promesa" "scittle/re-frame" "scittle/reagent" "scittle/replicant"]))))

                        (test "does not include internal libraries"
                              (fn []
                                (let [libs-list (libs/available-libraries)]
                                  (-> (expect libs-list)
                                      (.not.toContain "scittle/core"))
                                  (-> (expect libs-list)
                                      (.not.toContain "scittle/react")))))))))

(describe "collect-lib-files"
          (fn []
            (test "collects files from single script"
                  (fn []
                    (let [scripts [{:script/inject ["scittle://pprint.js"]}]
                          result (libs/collect-lib-files scripts)]
                      (-> (expect result)
                          (.toEqual ["scittle.pprint.js"])))))

            (test "collects files with dependencies"
                  (fn []
                    (let [scripts [{:script/inject ["scittle://reagent.js"]}]
                          result (libs/collect-lib-files scripts)]
                      (-> (expect result)
                          (.toEqual ["react.production.min.js"
                                     "react-dom.production.min.js"
                                     "scittle.reagent.js"])))))

            (test "deduplicates files across scripts"
                  (fn []
                    (let [scripts [{:script/inject ["scittle://reagent.js"]}
                                   {:script/inject ["scittle://pprint.js" "scittle://reagent.js"]}]
                          result (libs/collect-lib-files scripts)]
                      ;; React and reagent from first script, pprint from second
                      ;; Reagent duplicate in second script is ignored
                      (-> (expect result)
                          (.toEqual ["react.production.min.js"
                                     "react-dom.production.min.js"
                                     "scittle.reagent.js"
                                     "scittle.pprint.js"])))))

            (test "handles scripts without requires"
                  (fn []
                    (let [scripts [{:script/name "no-requires"}
                                   {:script/inject []}]
                          result (libs/collect-lib-files scripts)]
                      (-> (expect result)
                          (.toEqual [])))))

            (test "ignores non-scittle URLs"
                  (fn []
                    (let [scripts [{:script/inject ["https://example.com/lib.js"
                                                     "scittle://pprint.js"]}]
                          result (libs/collect-lib-files scripts)]
                      (-> (expect result)
                          (.toEqual ["scittle.pprint.js"])))))

            (test "handles transitive dependencies correctly"
                  (fn []
                    (let [scripts [{:script/inject ["scittle://re-frame.js"]}]
                          result (libs/collect-lib-files scripts)]
                      ;; re-frame needs reagent which needs react
                      (-> (expect result)
                          (.toEqual ["react.production.min.js"
                                     "react-dom.production.min.js"
                                     "scittle.reagent.js"
                                     "scittle.re-frame.js"])))))))
