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
                :script/description (.-description s)
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
       :description (:script/description script)
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

;; ============================================================
;; Built-in script detection
;; ============================================================

(def builtin-id-prefix "epupp-builtin-")

(defn builtin-script-id?
  "Check if a script ID is a built-in script ID."
  [script-id]
  (and script-id (.startsWith script-id builtin-id-prefix)))

(defn builtin-script?
  "Check if a script is a built-in script by ID prefix."
  [script]
  (builtin-script-id? (:script/id script)))

;; ============================================================
;; Script ID generation
;; ============================================================

(defn generate-script-id
  "Generate a stable, unique script ID based on timestamp.
   The ID is immutable once created - it does not change when the script is renamed."
  []
  (str "script-" (.now js/Date)))

;; ============================================================
;; Script name normalization
;; ============================================================

(defn normalize-script-name
  "Normalize a script name to a consistent format for uniqueness.
   - Lowercase
   - Replace spaces, dashes, and dots with underscores
   - Preserve `/` for namespace-like paths (e.g., my_project/utils.cljs)
   - Append .cljs extension
   - Remove invalid characters"
  [input-name]
  (let [;; Strip .cljs extension if present (we'll add it back)
        base-name (if (.endsWith input-name ".cljs")
                    (.slice input-name 0 -5)
                    input-name)]
    (-> base-name
        (.toLowerCase)
        (.replace (js/RegExp. "[\\s.-]+" "g") "_")
        (.replace (js/RegExp. "[^a-z0-9_/]" "g") "")
        (str ".cljs"))))

;; ============================================================
;; Debug: Expose for console testing
;; ============================================================

(set! js/globalThis.scriptUtils
      #js {:parse_scripts parse-scripts
           :script__GT_js script->js
           :pattern__GT_regex pattern->regex
           :url_matches_pattern_QMARK_ url-matches-pattern?
           :url_matches_any_pattern_QMARK_ url-matches-any-pattern?
           :get_matching_pattern get-matching-pattern
           :pattern_approved_QMARK_ pattern-approved?
           :get_required_origins get-required-origins
           :builtin_script_id_QMARK_ builtin-script-id?
           :builtin_script_QMARK_ builtin-script?
           :generate_script_id generate-script-id
           :normalize_script_name normalize-script-name})


