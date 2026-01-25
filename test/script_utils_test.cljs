(ns script-utils-test
  "Unit and property-style tests for script name validation."
  (:require ["vitest" :refer [describe test expect]]
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
