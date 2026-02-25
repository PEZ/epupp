(ns scittle-libs-test
  (:require ["vitest" :refer [describe test expect]]
            [scittle-libs :as libs]))

;; ============================================================
;; Test Functions
;; ============================================================

;; scittle-url? tests

(defn- test-scittle-url-returns-true-for-scittle-urls []
  (-> (expect (libs/scittle-url? "scittle://pprint.js"))
      (.toBe true))
  (-> (expect (libs/scittle-url? "scittle://reagent.js"))
      (.toBe true)))

(defn- test-scittle-url-returns-false-for-other-urls []
  (-> (expect (libs/scittle-url? "https://example.com/lib.js"))
      (.toBe false))
  (-> (expect (libs/scittle-url? "http://localhost/test.js"))
      (.toBe false)))

(defn- test-scittle-url-returns-false-for-nil-and-non-strings []
  (-> (expect (libs/scittle-url? nil))
      (.toBe false))
  (-> (expect (libs/scittle-url? 123))
      (.toBe false)))

;; resolve-scittle-url tests

(defn- test-resolve-scittle-url-resolves-valid-urls []
  (-> (expect (libs/resolve-scittle-url "scittle://pprint.js"))
      (.toBe "scittle/pprint"))
  (-> (expect (libs/resolve-scittle-url "scittle://reagent.js"))
      (.toBe "scittle/reagent"))
  (-> (expect (libs/resolve-scittle-url "scittle://js-interop.js"))
      (.toBe "scittle/js-interop")))

(defn- test-resolve-scittle-url-returns-nil-for-unknown-library []
  (-> (expect (libs/resolve-scittle-url "scittle://unknown.js"))
      (.toBeFalsy)))

(defn- test-resolve-scittle-url-returns-nil-for-non-scittle-urls []
  (-> (expect (libs/resolve-scittle-url "https://example.com/lib.js"))
      (.toBeFalsy)))

(defn- test-resolve-scittle-url-returns-nil-for-internal-libraries []
  (-> (expect (libs/resolve-scittle-url "scittle://core.js"))
      (.toBeFalsy))
  (-> (expect (libs/resolve-scittle-url "scittle://react.js"))
      (.toBeFalsy)))

(defn- test-resolve-scittle-url-returns-nil-for-nil-input []
  (-> (expect (libs/resolve-scittle-url nil))
      (.toBeFalsy)))

;; get-library-files tests

(defn- test-get-library-files-returns-single-file-for-standard-library []
  (-> (expect (libs/get-library-files :scittle/pprint))
      (.toEqual ["scittle.pprint.js"])))

(defn- test-get-library-files-returns-multiple-files-for-react []
  (-> (expect (libs/get-library-files :scittle/react))
      (.toEqual ["react.production.min.js" "react-dom.production.min.js"])))

(defn- test-get-library-files-returns-nil-for-unknown-library []
  (-> (expect (libs/get-library-files :scittle/unknown))
      (.toBeFalsy)))

;; resolve-dependencies tests

(defn- test-resolve-dependencies-returns-single-library-for-only-core-dep []
  (-> (expect (libs/resolve-dependencies :scittle/pprint))
      (.toEqual ["scittle/pprint"])))

(defn- test-resolve-dependencies-returns-library-for-reagent []
  (-> (expect (libs/resolve-dependencies :scittle/reagent))
      (.toEqual ["scittle/reagent"])))

(defn- test-resolve-dependencies-returns-transitive-deps-for-re-frame []
  (-> (expect (libs/resolve-dependencies :scittle/re-frame))
      (.toEqual ["scittle/reagent" "scittle/re-frame"])))

;; expand-inject tests

(defn- test-expand-inject-expands-simple-library-without-react []
  (let [result (libs/expand-inject "scittle://pprint.js")]
    (-> (expect (:inject/lib result))
        (.toBe "scittle/pprint"))
    (-> (expect (:inject/files result))
        (.toEqual ["scittle.pprint.js"]))))

(defn- test-expand-inject-expands-library-with-react-dependency []
  (let [result (libs/expand-inject "scittle://reagent.js")]
    (-> (expect (:inject/lib result))
        (.toBe "scittle/reagent"))
    (-> (expect (:inject/files result))
        (.toEqual ["react.production.min.js"
                   "react-dom.production.min.js"
                   "scittle.reagent.js"]))))

(defn- test-expand-inject-expands-library-with-transitive-dependency []
  (let [result (libs/expand-inject "scittle://re-frame.js")]
    (-> (expect (:inject/lib result))
        (.toBe "scittle/re-frame"))
    (-> (expect (:inject/files result))
        (.toEqual ["react.production.min.js"
                   "react-dom.production.min.js"
                   "scittle.reagent.js"
                   "scittle.re-frame.js"]))))

(defn- test-expand-inject-returns-nil-for-internal-library []
  (-> (expect (libs/expand-inject "scittle://core.js"))
      (.toBeFalsy)))

(defn- test-expand-inject-returns-nil-for-unknown-library []
  (-> (expect (libs/expand-inject "scittle://unknown.js"))
      (.toBeFalsy)))

(defn- test-expand-inject-returns-nil-for-non-scittle-url []
  (-> (expect (libs/expand-inject "https://example.com/lib.js"))
      (.toBeFalsy)))

;; available-libraries tests

(defn- test-available-libraries-returns-sorted-list []
  (-> (expect (libs/available-libraries))
      (.toEqual ["scittle/cljs-ajax" "scittle/js-interop" "scittle/pprint"
                 "scittle/promesa" "scittle/re-frame" "scittle/reagent" "scittle/replicant"])))

(defn- test-available-libraries-does-not-include-internal-libraries []
  (let [libs-list (libs/available-libraries)]
    (-> (expect libs-list)
        (.not.toContain "scittle/core"))
    (-> (expect libs-list)
        (.not.toContain "scittle/react"))))

;; collect-lib-files tests

(defn- test-collect-lib-files-collects-files-from-single-script []
  (let [scripts [{:script/inject ["scittle://pprint.js"]}]
        result (libs/collect-lib-files scripts)]
    (-> (expect result)
        (.toEqual ["scittle.pprint.js"]))))

(defn- test-collect-lib-files-collects-files-with-dependencies []
  (let [scripts [{:script/inject ["scittle://reagent.js"]}]
        result (libs/collect-lib-files scripts)]
    (-> (expect result)
        (.toEqual ["react.production.min.js"
                   "react-dom.production.min.js"
                   "scittle.reagent.js"]))))

(defn- test-collect-lib-files-deduplicates-files-across-scripts []
  (let [scripts [{:script/inject ["scittle://reagent.js"]}
                 {:script/inject ["scittle://pprint.js" "scittle://reagent.js"]}]
        result (libs/collect-lib-files scripts)]
    (-> (expect result)
        (.toEqual ["react.production.min.js"
                   "react-dom.production.min.js"
                   "scittle.reagent.js"
                   "scittle.pprint.js"]))))

(defn- test-collect-lib-files-handles-scripts-without-requires []
  (let [scripts [{:script/name "no-requires"}
                 {:script/inject []}]
        result (libs/collect-lib-files scripts)]
    (-> (expect result)
        (.toEqual []))))

(defn- test-collect-lib-files-ignores-non-scittle-urls []
  (let [scripts [{:script/inject ["https://example.com/lib.js"
                                   "scittle://pprint.js"]}]
        result (libs/collect-lib-files scripts)]
    (-> (expect result)
        (.toEqual ["scittle.pprint.js"]))))

(defn- test-collect-lib-files-handles-transitive-dependencies-correctly []
  (let [scripts [{:script/inject ["scittle://re-frame.js"]}]
        result (libs/collect-lib-files scripts)]
    (-> (expect result)
        (.toEqual ["react.production.min.js"
                   "react-dom.production.min.js"
                   "scittle.reagent.js"
                   "scittle.re-frame.js"]))))

;; ============================================================
;; Test Registration
;; ============================================================

(describe "scittle-libs"
          (fn []
            (describe "scittle-url?"
                      (fn []
                        (test "returns true for scittle:// URLs" test-scittle-url-returns-true-for-scittle-urls)
                        (test "returns false for other URLs" test-scittle-url-returns-false-for-other-urls)
                        (test "returns false for nil and non-strings" test-scittle-url-returns-false-for-nil-and-non-strings)))

            (describe "resolve-scittle-url"
                      (fn []
                        (test "resolves valid scittle:// URLs to library key" test-resolve-scittle-url-resolves-valid-urls)
                        (test "returns nil for unknown library" test-resolve-scittle-url-returns-nil-for-unknown-library)
                        (test "returns nil for non-scittle URLs" test-resolve-scittle-url-returns-nil-for-non-scittle-urls)
                        (test "returns nil for internal libraries" test-resolve-scittle-url-returns-nil-for-internal-libraries)
                        (test "returns nil for nil input" test-resolve-scittle-url-returns-nil-for-nil-input)))

            (describe "get-library-files"
                      (fn []
                        (test "returns single file for standard library" test-get-library-files-returns-single-file-for-standard-library)
                        (test "returns multiple files for react" test-get-library-files-returns-multiple-files-for-react)
                        (test "returns nil for unknown library" test-get-library-files-returns-nil-for-unknown-library)))

            (describe "resolve-dependencies"
                      (fn []
                        (test "returns single library for library with only core dep" test-resolve-dependencies-returns-single-library-for-only-core-dep)
                        (test "returns library for reagent (React is internal)" test-resolve-dependencies-returns-library-for-reagent)
                        (test "returns transitive deps for re-frame" test-resolve-dependencies-returns-transitive-deps-for-re-frame)))

            (describe "expand-inject"
                      (fn []
                        (test "expands simple library without React" test-expand-inject-expands-simple-library-without-react)
                        (test "expands library with React dependency" test-expand-inject-expands-library-with-react-dependency)
                        (test "expands library with transitive dependency" test-expand-inject-expands-library-with-transitive-dependency)
                        (test "returns nil for internal library" test-expand-inject-returns-nil-for-internal-library)
                        (test "returns nil for unknown library" test-expand-inject-returns-nil-for-unknown-library)
                        (test "returns nil for non-scittle URL" test-expand-inject-returns-nil-for-non-scittle-url)))

            (describe "available-libraries"
                      (fn []
                        (test "returns sorted list of available libraries" test-available-libraries-returns-sorted-list)
                        (test "does not include internal libraries" test-available-libraries-does-not-include-internal-libraries)))))

(describe "collect-lib-files"
          (fn []
            (test "collects files from single script" test-collect-lib-files-collects-files-from-single-script)
            (test "collects files with dependencies" test-collect-lib-files-collects-files-with-dependencies)
            (test "deduplicates files across scripts" test-collect-lib-files-deduplicates-files-across-scripts)
            (test "handles scripts without requires" test-collect-lib-files-handles-scripts-without-requires)
            (test "ignores non-scittle URLs" test-collect-lib-files-ignores-non-scittle-urls)
            (test "handles transitive dependencies correctly" test-collect-lib-files-handles-transitive-dependencies-correctly)))

;; collect-lib-namespaces tests

(defn- test-collect-lib-namespaces-returns-namespaces-for-single-lib []
  (let [scripts [{:script/inject ["scittle://replicant.js"]}]
        result (libs/collect-lib-namespaces scripts)]
    (-> (expect result)
        (.toEqual ["replicant.dom"]))))

(defn- test-collect-lib-namespaces-returns-namespaces-for-multiple-libs []
  (let [scripts [{:script/inject ["scittle://replicant.js" "scittle://pprint.js"]}]
        result (libs/collect-lib-namespaces scripts)]
    (-> (expect result)
        (.toEqual ["replicant.dom" "cljs.pprint"]))))

(defn- test-collect-lib-namespaces-returns-empty-for-no-injects []
  (let [scripts [{:script/name "no-requires"}]
        result (libs/collect-lib-namespaces scripts)]
    (-> (expect result)
        (.toEqual []))))

(defn- test-collect-lib-namespaces-ignores-non-scittle-urls []
  (let [scripts [{:script/inject ["https://example.com/lib.js" "scittle://promesa.js"]}]
        result (libs/collect-lib-namespaces scripts)]
    (-> (expect result)
        (.toEqual ["promesa.core"]))))

(defn- test-collect-lib-namespaces-collects-across-multiple-scripts []
  (let [scripts [{:script/inject ["scittle://reagent.js"]}
                 {:script/inject ["scittle://pprint.js"]}]
        result (libs/collect-lib-namespaces scripts)]
    (-> (expect result)
        (.toEqual ["reagent.core" "cljs.pprint"]))))

(describe "collect-lib-namespaces"
          (fn []
            (test "returns namespaces for single lib" test-collect-lib-namespaces-returns-namespaces-for-single-lib)
            (test "returns namespaces for multiple libs" test-collect-lib-namespaces-returns-namespaces-for-multiple-libs)
            (test "returns empty for no injects" test-collect-lib-namespaces-returns-empty-for-no-injects)
            (test "ignores non-scittle URLs" test-collect-lib-namespaces-ignores-non-scittle-urls)
            (test "collects across multiple scripts" test-collect-lib-namespaces-collects-across-multiple-scripts)))
