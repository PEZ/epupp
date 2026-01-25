(ns script-utils-test
  "Unit and property-style tests for script name validation."
  (:require ["vitest" :refer [describe test expect]]
            [manifest-parser :as mp]
            [script-utils :as script-utils]))

;; ============================================================
;; Helpers for deterministic property-style tests
;; ============================================================

(defn- lcg-next [seed]
  (mod (+ (* seed 1664525) 1013904223) 4294967296))

(defn- lcg-rand-int [seed n]
  (let [next (lcg-next seed)]
    [next (mod next n)]))

(defn- rand-char [seed]
  (let [chars "abcdefghijklmnopqrstuvwxyz0123456789_"
        [seed idx] (lcg-rand-int seed (.-length chars))]
    [seed (.charAt chars idx)]))

(defn- gen-segment [seed]
  (let [[seed len] (lcg-rand-int seed 8)
        len (+ 1 len)]
    (loop [seed seed i 0 out ""]
      (if (< i len)
        (let [[seed ch] (rand-char seed)]
          (recur seed (inc i) (str out ch)))
        [seed out]))))

(defn- gen-valid-name [seed]
  (let [[seed seg-count] (lcg-rand-int seed 3)
        seg-count (+ 1 seg-count)]
    (loop [seed seed i 0 segs []]
      (if (< i seg-count)
        (let [[seed seg] (gen-segment seed)
              seg (if (and (= i 0) (= seg "epupp")) "epuppx" seg)]
          (recur seed (inc i) (conj segs seg)))
        [seed (str (.join segs "/") ".cljs")]))))

;; ============================================================
;; validate-script-name tests
;; ============================================================

(describe "validate-script-name"
  (fn []
    (test "accepts valid names"
      (fn []
        (-> (expect (script-utils/validate-script-name "test.cljs"))
            (.toBe nil))
        (-> (expect (script-utils/validate-script-name "folder/test.cljs"))
            (.toBe nil))))

    (test "rejects reserved namespace"
      (fn []
        (-> (expect (script-utils/validate-script-name "epupp/test.cljs"))
            (.toContain "reserved namespace"))))

    (test "rejects leading slash"
      (fn []
        (-> (expect (script-utils/validate-script-name "/test.cljs"))
            (.toContain "start with '/'"))))

    (test "rejects dot-slash and dot-dot-slash"
      (fn []
        (-> (expect (script-utils/validate-script-name "./test.cljs"))
            (.toContain "./' or '../'"))
        (-> (expect (script-utils/validate-script-name "../test.cljs"))
            (.toContain "./' or '../'"))
        (-> (expect (script-utils/validate-script-name "folder/../test.cljs"))
            (.toContain "./' or '../'"))))

    (test "property: valid names are accepted"
      (fn []
        (loop [seed 1 idx 0]
          (when (< idx 200)
            (let [[seed name] (gen-valid-name seed)]
              (-> (expect (script-utils/validate-script-name name))
                  (.toBe nil))
              (recur seed (inc idx)))))))

    (test "property: reserved namespace is rejected"
      (fn []
        (loop [seed 2 idx 0]
          (when (< idx 200)
            (let [[seed name] (gen-valid-name seed)
                  bad-name (str "epupp/" name)
                  err (script-utils/validate-script-name bad-name)]
              (-> (expect err) (.toContain "reserved namespace"))
              (recur seed (inc idx)))))))

    (test "property: leading slash is rejected"
      (fn []
        (loop [seed 3 idx 0]
          (when (< idx 200)
            (let [[seed name] (gen-valid-name seed)
                  bad-name (str "/" name)
                  err (script-utils/validate-script-name bad-name)]
              (-> (expect err) (.toContain "start with '/'"))
              (recur seed (inc idx)))))))

    (test "property: path traversal is rejected"
      (fn []
        (loop [seed 4 idx 0]
          (when (< idx 200)
            (let [[seed name] (gen-valid-name seed)
                  bad-name (str "foo/../" name)
                  err (script-utils/validate-script-name bad-name)]
              (-> (expect err) (.toContain "./' or '../'"))
              (recur seed (inc idx)))))))))

(describe "derive-script-fields"
  (fn []
    (test "derives fields from manifest"
      (fn []
        (let [code "^{:epupp/script-name \"derived.cljs\"\n  :epupp/auto-run-match \"https://example.com/*\"\n  :epupp/description \"Example\"\n  :epupp/run-at \"document-end\"\n  :epupp/inject \"scittle://reagent.js\"}\n(ns derived)"
              script {:script/id "script-1" :script/code code}
              manifest (mp/extract-manifest code)
              derived (script-utils/derive-script-fields script manifest)]
          (-> (expect (:script/name derived))
              (.toBe "derived.cljs"))
          (-> (expect (:script/match derived))
              (.toEqual ["https://example.com/*"]))
          (-> (expect (:script/description derived))
              (.toBe "Example"))
          (-> (expect (:script/run-at derived))
              (.toBe "document-end"))
          (-> (expect (:script/inject derived))
              (.toEqual ["scittle://reagent.js"])))))

    (test "manifest without auto-run-match clears match"
      (fn []
        (let [code "^{:epupp/script-name \"manual.cljs\"}\n(ns manual)"
              script {:script/id "script-2"
                      :script/code code
                      :script/match ["https://old.example/*"]}
              manifest (mp/extract-manifest code)
              derived (script-utils/derive-script-fields script manifest)]
          (-> (expect (:script/match derived))
              (.toEqual [])))))

    (test "nil manifest preserves existing fields"
      (fn []
        (let [script {:script/id "script-3"
                      :script/code "(ns no-manifest)"
                      :script/name "old.cljs"
                      :script/description "Old"
                      :script/match ["https://old.example/*"]
                      :script/run-at "document-start"
                      :script/inject ["scittle://reagent.js"]}
              derived (script-utils/derive-script-fields script nil)]
          (-> (expect (:script/name derived))
              (.toBe "old.cljs"))
          (-> (expect (:script/match derived))
              (.toEqual ["https://old.example/*"]))
          (-> (expect (:script/run-at derived))
              (.toBe "document-start"))
          (-> (expect (:script/inject derived))
              (.toEqual ["scittle://reagent.js"])))))))

(describe "parse-scripts"
  (fn []
    (test "derives fields when extractor is provided"
      (fn []
        (let [code "^{:epupp/script-name \"derived.cljs\"\n  :epupp/auto-run-match \"https://example.com/*\"}\n(ns derived)"
              js-scripts #js [#js {:id "script-1"
                                   :code code
                                   :enabled false
                                   :created "2026-01-01T00:00:00.000Z"
                                   :modified "2026-01-02T00:00:00.000Z"
                                   :builtin false}]
              scripts (script-utils/parse-scripts js-scripts {:extract-manifest mp/extract-manifest})
              script (first scripts)]
          (-> (expect (:script/name script))
              (.toBe "derived.cljs"))
          (-> (expect (:script/match script))
              (.toEqual ["https://example.com/*"]))
          (-> (expect (:script/enabled script))
              (.toBe false)))))))

(describe "script->js"
  (fn []
    (test "emits fields needed for early injection loader"
      (fn []
        (let [script {:script/id "script-1"
                      :script/name "derived.cljs"
                      :script/description "Example"
                      :script/match ["https://example.com/*"]
                      :script/code "(ns derived)"
                      :script/enabled true
                      :script/created "2026-01-01T00:00:00.000Z"
                      :script/modified "2026-01-02T00:00:00.000Z"
                      :script/run-at "document-end"
                      :script/inject ["scittle://reagent.js"]
                      :script/builtin? true}
              js-script (script-utils/script->js script)
              keys (js/Object.keys js-script)]
          ;; Core fields + runAt + match for early loader
          (-> (expect (.-length keys))
              (.toBe 8))
          (-> (expect (.includes keys "id"))
              (.toBe true))
          (-> (expect (.includes keys "code"))
              (.toBe true))
          (-> (expect (.includes keys "enabled"))
              (.toBe true))
          (-> (expect (.includes keys "created"))
              (.toBe true))
          (-> (expect (.includes keys "modified"))
              (.toBe true))
          (-> (expect (.includes keys "builtin"))
              (.toBe true))
          ;; Early loader needs runAt and match
          (-> (expect (.includes keys "runAt"))
              (.toBe true))
          (-> (expect (.includes keys "match"))
              (.toBe true))
          (-> (expect (aget js-script "runAt"))
              (.toBe "document-end"))
          (-> (expect (aget js-script "match"))
              (.toEqual #js ["https://example.com/*"]))
          ;; UI-only fields are still excluded
          (-> (expect (aget js-script "name"))
              (.toBeUndefined))
          (-> (expect (aget js-script "description"))
              (.toBeUndefined))
          (-> (expect (aget js-script "inject"))
              (.toBeUndefined))
          (-> (expect (.-builtin js-script))
              (.toBe true)))))))
