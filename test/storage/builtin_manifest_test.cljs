(ns storage.builtin-manifest-test
  "Tests for building built-in scripts from manifest metadata."
  (:require ["vitest" :refer [describe test expect]]
            [builtin-scripts :as builtins]))

;; ============================================================
;; Test Functions
;; ============================================================

(defn- bundled-result [id name code]
  (builtins/build-bundled-script {:script/id id
                                 :path (str "userscripts/" name)
                                 :name name}
                                code))

(defn- assert-inject [id name code expected-inject]
  (-> (expect (:script/inject (bundled-result id name code)))
      (.toEqual expected-inject)))

(defn- assert-match [id name code expected-match]
  (-> (expect (:script/match (bundled-result id name code)))
      (.toEqual expected-match)))

(defn- normal-result []
  (bundled-result "builtin-normal" "normal.cljs"
                  "{:epupp/script-name \"normal.cljs\"}

(ns normal)"))

(defn- test-build-bundled-complete-manifest-all-fields []
  (let [bundled {:script/id "builtin-1"
                 :path "userscripts/test.cljs"
                 :name "test.cljs"}
        code "{:epupp/script-name \"complete.cljs\"
 :epupp/description \"A complete script\"
 :epupp/auto-run-match [\"https://example.com/*\" \"https://test.com/*\"]
 :epupp/run-at \"document-start\"
 :epupp/inject [\"scittle://reagent.js\" \"scittle://re-frame.js\"]}

(ns test-script)
(println \"hello\")"
        result (builtins/build-bundled-script bundled code)]
    (-> (expect (:script/id result))
        (.toBe "builtin-1"))
    (-> (expect (:script/code result))
        (.toBe code))
    (-> (expect (:script/builtin? result))
        (.toBe true))
    (-> (expect (:script/name result))
        (.toBe "complete.cljs"))
    (-> (expect (:script/description result))
        (.toBe "A complete script"))
    (-> (expect (:script/match result))
        (.toEqual ["https://example.com/*" "https://test.com/*"]))
    (-> (expect (:script/run-at result))
        (.toBe "document-start"))
    (-> (expect (:script/inject result))
        (.toEqual ["scittle://reagent.js" "scittle://re-frame.js"]))))

(defn- test-build-bundled-minimal-manifest-only-script-name []
  (let [bundled {:script/id "builtin-2"
                 :path "userscripts/minimal.cljs"
                 :name "minimal.cljs"}
        code "{:epupp/script-name \"minimal.cljs\"}

(ns minimal)
(println \"minimal\")"
        result (builtins/build-bundled-script bundled code)]
    (-> (expect (:script/id result))
        (.toBe "builtin-2"))
    (-> (expect (:script/code result))
        (.toBe code))
    (-> (expect (:script/builtin? result))
        (.toBe true))
    (-> (expect (:script/name result))
        (.toBe "minimal.cljs"))
    (-> (expect (contains? result :script/description))
        (.toBe false))
    (-> (expect (:script/match result))
        (.toEqual []))
    (-> (expect (contains? result :script/inject))
        (.toBe false))))

(defn- test-build-bundled-manifest-with-string-inject []
  (assert-inject "builtin-3" "string-inject.cljs"
                 "{:epupp/script-name \"string-inject.cljs\"
 :epupp/inject \"scittle://reagent.js\"}

(ns string-inject)"
                 ["scittle://reagent.js"]))

(defn- test-build-bundled-manifest-with-array-inject []
  (assert-inject "builtin-4" "array-inject.cljs"
                 "{:epupp/script-name \"array-inject.cljs\"
 :epupp/inject [\"scittle://reagent.js\" \"scittle://pprint.js\"]}

(ns array-inject)"
                 ["scittle://reagent.js" "scittle://pprint.js"]))

(defn- test-build-bundled-manifest-with-match-patterns []
  (assert-match "builtin-5" "with-match.cljs"
                "{:epupp/script-name \"with-match.cljs\"
 :epupp/auto-run-match \"https://github.com/*\"}

(ns with-match)"
                ["https://github.com/*"]))

(defn- test-build-bundled-manifest-without-match-manual-only []
  (assert-match "builtin-6" "manual-only.cljs"
                "{:epupp/script-name \"manual-only.cljs\"
 :epupp/description \"Manual execution only\"}

(ns manual-only)"
                []))

(defn- test-build-bundled-invalid-run-at-defaults-to-document-idle []
  (let [bundled {:script/id "builtin-7"
                 :path "userscripts/invalid-run-at.cljs"
                 :name "invalid-run-at.cljs"}
        code "{:epupp/script-name \"invalid-run-at.cljs\"
 :epupp/run-at \"invalid-timing\"}

(ns invalid-run-at)"
        result (builtins/build-bundled-script bundled code)]
    (-> (expect (:script/run-at result))
        (.toBe "document-idle"))))

(defn- test-build-bundled-uses-fallback-name-when-no-manifest []
  (let [bundled {:script/id "builtin-8"
                 :path "userscripts/no-manifest.cljs"
                 :name "fallback-name.cljs"}
        code "(ns no-manifest)
(println \"no manifest at all\")"
        result (builtins/build-bundled-script bundled code)]
    (-> (expect (:script/name result))
        (.toBe "fallback-name.cljs"))
    (-> (expect (:script/builtin? result))
        (.toBe true))
    (-> (expect (contains? result :script/inject))
        (.toBe false))))

(defn- test-build-bundled-manifest-with-string-match []
  (assert-match "builtin-9" "string-match.cljs"
                "{:epupp/script-name \"string-match.cljs\"
 :epupp/auto-run-match \"https://example.com/*\"}

(ns string-match)"
                ["https://example.com/*"]))

(defn- test-build-bundled-manifest-with-empty-match-array []
  (assert-match "builtin-10" "empty-match.cljs"
                "{:epupp/script-name \"empty-match.cljs\"
 :epupp/auto-run-match []}

(ns empty-match)"
                []))

(defn- test-build-bundled-always-enabled-propagated []
  (let [bundled {:script/id "builtin-sponsor"
                 :path "userscripts/sponsor.cljs"
                 :name "sponsor.cljs"
                 :always-enabled? true}
        code "{:epupp/script-name \"sponsor.cljs\"
 :epupp/auto-run-match \"https://github.com/sponsors/PEZ*\"}

(ns epupp.sponsor)"
        result (builtins/build-bundled-script bundled code)]
    (-> (expect (:script/always-enabled? result))
        (.toBe true))))

(defn- test-build-bundled-without-always-enabled []
  (-> (expect (contains? (normal-result) :script/always-enabled?))
      (.toBe false)))

(defn- test-build-bundled-special-flags-propagated []
  (let [bundled {:script/id "builtin-installer"
                 :path "userscripts/epupp/web_userscript_installer.cljs"
                 :name "epupp/web_userscript_installer.cljs"
                 :special? true
                 :web-installer-scan true}
        code "{:epupp/script-name \"epupp/web_userscript_installer.cljs\"
 :epupp/description \"Web Userscript Installer\"}

(ns epupp.web-installer)"
        result (builtins/build-bundled-script bundled code)]
    (-> (expect (:script/special? result))
        (.toBe true))
    (-> (expect (:script/web-installer-scan result))
        (.toBe true))))

(defn- test-build-bundled-internal-helpers-library []
  (let [bundled {:script/id "epupp-builtin-internal-helpers"
                 :path "userscripts/epupp/internal/helpers.cljs"
                 :name "epupp/internal/helpers.cljs"
                 :always-enabled? true}
        code "{:epupp/script-name \"epupp/internal/helpers.cljs\"
   :epupp/description \"Internal helpers for built-in Epupp scripts: bridge messaging and manifest parsing\"}

  (ns epupp.internal.helpers)"
        result (builtins/build-bundled-script bundled code)]
    (-> (expect (:script/id result))
        (.toBe "epupp-builtin-internal-helpers"))
    (-> (expect (:script/name result))
        (.toBe "epupp/internal/helpers.cljs"))
    (-> (expect (:script/description result))
        (.toBe "Internal helpers for built-in Epupp scripts: bridge messaging and manifest parsing"))
    (-> (expect (:script/always-enabled? result))
        (.toBe true))
    (-> (expect (:script/match result))
        (.toEqual []))))

(defn- test-build-bundled-without-special-flags []
  (let [result (normal-result)]
    (-> (expect (contains? result :script/special?))
        (.toBe false))
    (-> (expect (contains? result :script/web-installer-scan))
        (.toBe false))))

;; ============================================================
;; Test Registration
;; ============================================================

(describe "Built-in script building from manifest"
          (fn []
            (test "complete manifest with all fields" test-build-bundled-complete-manifest-all-fields)
            (test "minimal manifest (only script-name)" test-build-bundled-minimal-manifest-only-script-name)
            (test "manifest with string inject" test-build-bundled-manifest-with-string-inject)
            (test "manifest with array inject" test-build-bundled-manifest-with-array-inject)
            (test "manifest with match patterns" test-build-bundled-manifest-with-match-patterns)
            (test "manifest without match (manual-only)" test-build-bundled-manifest-without-match-manual-only)
            (test "invalid run-at defaults to document-idle" test-build-bundled-invalid-run-at-defaults-to-document-idle)
            (test "uses fallback name when no manifest" test-build-bundled-uses-fallback-name-when-no-manifest)
            (test "manifest with string match" test-build-bundled-manifest-with-string-match)
            (test "manifest with empty match array" test-build-bundled-manifest-with-empty-match-array)
            (test "always-enabled? propagated from catalog" test-build-bundled-always-enabled-propagated)
            (test "without always-enabled? omits the key" test-build-bundled-without-always-enabled)
            (test "special flags propagated from catalog" test-build-bundled-special-flags-propagated)
            (test "internal helpers library manifest builds expected built-in fields" test-build-bundled-internal-helpers-library)
            (test "without special flags omits the keys" test-build-bundled-without-special-flags)))
