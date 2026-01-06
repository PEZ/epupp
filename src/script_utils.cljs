(ns script-utils
  "Shared utilities for userscript data transformation and URL matching.

   This module contains pure functions with no external dependencies,
   enabling use across popup, storage, url-matching, and background modules
   without circular dependency issues.")

;; ============================================================
;; Script data transformation
;; ============================================================

(defn- js-arr->vec
  "Convert JS array to Clojure vector (handles nil)"
  [arr]
  (if arr (vec arr) []))

(defn parse-scripts
  "Convert JS scripts array to Clojure with namespaced keys"
  [js-scripts]
  (->> (js-arr->vec js-scripts)
       (mapv (fn [s]
               {:script/id (.-id s)
                :script/name (.-name s)
                :script/match (js-arr->vec (.-match s))
                :script/code (.-code s)
                :script/enabled (.-enabled s)
                :script/created (.-created s)
                :script/modified (.-modified s)
                :script/approved-patterns (js-arr->vec (.-approvedPatterns s))}))))

(defn script->js
  "Convert script map to JS object with simple keys for storage"
  [script]
  #js {:id (:script/id script)
       :name (:script/name script)
       :match (clj->js (:script/match script))
       :code (:script/code script)
       :enabled (:script/enabled script)
       :created (:script/created script)
       :modified (:script/modified script)
       :approvedPatterns (clj->js (:script/approved-patterns script))})

;; ============================================================
;; URL pattern matching
;; ============================================================

(defn- escape-regex
  "Escape special regex characters except * which we handle specially"
  [s]
  ;; Escape: . + ? ^ $ { } ( ) | [ ] \
  ;; Don't escape * - we convert it to .*
  (.replace s (js/RegExp. "[.+?^${}()|[\\]\\\\]" "g") "\\$&"))

(defn pattern->regex
  "Convert a match pattern to a RegExp.

   Examples:
   - '*://github.com/*' -> matches http://github.com/... and https://github.com/...
   - 'https://*.example.com/*' -> matches any subdomain
   - '<all_urls>' -> matches everything"
  [pattern]
  (cond
    ;; Special pattern for all URLs
    (= pattern "<all_urls>")
    (js/RegExp. "^https?://.*$")

    ;; Standard match pattern
    :else
    (let [;; First escape regex special chars (except *)
          escaped (escape-regex pattern)
          ;; Then convert * to .* for wildcard matching
          with-wildcards (.replace escaped (js/RegExp. "\\*" "g") ".*")]
      (js/RegExp. (str "^" with-wildcards "$")))))

(defn url-matches-pattern?
  "Check if a URL matches a single pattern"
  [url pattern]
  (let [regex (pattern->regex pattern)]
    (.test regex url)))

(defn url-matches-any-pattern?
  "Check if a URL matches any pattern in the list"
  [url patterns]
  (some #(url-matches-pattern? url %) patterns))

;; ============================================================
;; Script query functions (pure, no storage access)
;; ============================================================

(defn get-matching-pattern
  "Find which pattern in a script matches the given URL.
   Returns the first matching pattern, or nil."
  [url script]
  (when url
    (->> (:script/match script)
         (filter #(url-matches-pattern? url %))
         first)))

(defn pattern-approved?
  "Check if a pattern is in the script's approved list"
  [script pattern]
  (some #(= % pattern) (:script/approved-patterns script)))

(defn get-required-origins
  "Extract unique origin patterns from a list of scripts.
   Used to determine which permissions need to be requested."
  [scripts]
  (->> scripts
       (mapcat :script/match)
       distinct
       vec))

(def ^:private install-manifest-marker
  ";; Scittle Tamper UserScript")

(defn- extract-discarded-edn-map
  "Extract the EDN map string from a Scittle Tamper userscript preamble.

   Expected format:

  ;; Scittle Tamper UserScript
  #_{:script-name \"...\"
    :site-match \"...\"
    :script-code [\"https://example.com/script.cljs\"]}

   Returns the full map string, including surrounding braces, or nil."
  [s]
  (let [marker-idx (.indexOf s install-manifest-marker)]
    (when (not= -1 marker-idx)
      (let [start-idx (.indexOf s "#_{" marker-idx)]
        (when (not= -1 start-idx)
          (let [start (+ start-idx 2)
                n (.-length s)]
            (loop [pos start
                   depth 0
                   in-str? false
                   esc? false]
              (when (< pos n)
                (let [ch (.charAt s pos)]
                  (cond
                    in-str?
                    (cond
                      esc? (recur (inc pos) depth true false)
                      (= ch "\\") (recur (inc pos) depth true true)
                      (= ch "\"") (recur (inc pos) depth false false)
                      :else (recur (inc pos) depth true false))

                    :else
                    (cond
                      (= ch "\"") (recur (inc pos) depth true false)
                      (= ch "{") (recur (inc pos) (inc depth) false false)
                      (= ch "}") (let [depth' (dec depth)]
                                   (if (zero? depth')
                                     (.slice s start (inc pos))
                                     (recur (inc pos) depth' false false)))
                      :else (recur (inc pos) depth false false))))))))))))

(defn- skip-ws
  [s i]
  (let [n (.-length s)]
    (loop [j i]
      (if (>= j n)
        j
        (let [ch (.charAt s j)]
          (if (or (= ch " ") (= ch "\n") (= ch "\t") (= ch "\r") (= ch ","))
            (recur (inc j))
            j))))))

(defn- read-keyword
  [s i]
  (let [n (.-length s)]
    (when (= (.charAt s i) ":")
      (loop [j (inc i)]
        (if (>= j n)
          [(.slice s (inc i) j) j]
          (let [ch (.charAt s j)]
            (if (or (= ch " ") (= ch "\n") (= ch "\t") (= ch "\r") (= ch "}") (= ch "]"))
              [(.slice s (inc i) j) j]
              (recur (inc j)))))))))

(defn- read-string-lit
  [s i]
  (when (= (.charAt s i) "\"")
    (let [n (.-length s)]
      (loop [j (inc i)
             esc? false
             out ""]
        (when (< j n)
          (let [ch (.charAt s j)]
            (cond
              esc?
              (let [out' (str out (case ch
                                   "n" "\n"
                                   "t" "\t"
                                   "r" "\r"
                                   "\"" "\""
                                   "\\" "\\"
                                   ch))]
                (recur (inc j) false out'))

              (= ch "\\")
              (recur (inc j) true out)

              (= ch "\"")
              [out (inc j)]

              :else
              (recur (inc j) false (str out ch)))))))))

(defn- read-vector-of-strings
  [s i]
  (when (= (.charAt s i) "[")
    (let [n (.-length s)]
      (loop [j (inc i)
             xs []]
        (let [j (skip-ws s j)
              ch (when (< j n) (.charAt s j))]
          (cond
            (nil? ch) nil
            (= ch "]") [xs (inc j)]
            :else
            (let [[v j2] (read-string-lit s j)]
              (recur j2 (conj xs v)))))))))

(defn- new-js-obj
  "Create an empty JavaScript object.
   Workaround for needing plain JS objects with dynamic keys."
  []
  #js {})

(defn parse-install-manifest
  "Parse a Scittle Tamper userscript install manifest from a text blob.

   Returns a map with keys like `:script-name`, `:site-match`, `:script-code`,
   or nil if no manifest is found."
  [s]
  (when-let [m (extract-discarded-edn-map s)]
    (let [inner (.slice m 1 (dec (.-length m)))
          n (.-length inner)
          allowed-keys ["script-name" "site-match" "script-code"]]
      (loop [i 0
             acc (new-js-obj)]
        (let [i (skip-ws inner i)]
          (if (>= i n)
            (when (not-empty (js/Object.keys acc)) acc)
            (let [[k i2] (read-keyword inner i)
                  i3 (skip-ws inner i2)
                  ch (when (< i3 n) (.charAt inner i3))
                  [v i4] (cond
                           (= ch "\"") (read-string-lit inner i3)
                           (= ch "[") (read-vector-of-strings inner i3)
                           :else [nil (inc i3)])]
              ;; Use aset for dynamic string keys (Squint map literals don't support this)
              (when (and k (some #(= % k) allowed-keys))
                (aset acc k v))
              (recur i4 acc))))))))

;; ============================================================
;; Debug: Expose for console testing
;; ============================================================

(set! js/globalThis.scriptUtils
      #js {:parse_scripts parse-scripts
           :script__GT_js script->js
           :parse_install_manifest parse-install-manifest
           :pattern__GT_regex pattern->regex
           :url_matches_pattern_QMARK_ url-matches-pattern?
           :url_matches_any_pattern_QMARK_ url-matches-any-pattern?
           :get_matching_pattern get-matching-pattern
           :pattern_approved_QMARK_ pattern-approved?
           :get_required_origins get-required-origins})
