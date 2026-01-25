(ns script-utils
  "Shared utilities for userscript data transformation and URL matching.

   This module contains pure functions with no external dependencies,
   enabling use across popup, storage, url-matching, and background modules
   without circular dependency issues.")

;; ============================================================
;; Script data transformation
;; ============================================================

(def valid-run-at-values
  "Set of valid run-at timing values for userscripts."
  #{"document-start" "document-end" "document-idle"})

(def default-run-at
  "Default run-at timing if not specified."
  "document-idle")

(defn normalize-run-at
  "Validate and normalize a run-at value.
   Returns the value if valid, otherwise returns default-run-at."
  [run-at]
  (if (contains? valid-run-at-values run-at)
    run-at
    default-run-at))

(defn- js-arr->vec
  "Convert JS array to Clojure vector (handles nil)"
  [arr]
  (if arr (vec arr) []))

(defn normalize-match-patterns
  "Normalize match patterns to a vector.
   Accepts a string (single pattern) or vector of patterns.
   Empty string or nil means no patterns (manual-only script).
   Returns a vector of pattern strings."
  [match]
  (cond
    (nil? match) []
    (and (string? match) (empty? match)) []
    (string? match) [match]
    (vector? match) match
    :else (vec match)))

(defn derive-script-fields
  "Derive script fields from manifest data.
   When manifest is present, it becomes the source of truth for derived fields."
  [script manifest]
  (let [has-manifest? (some? manifest)
        manifest-name (or (get manifest "script-name")
                          (get manifest "raw-script-name"))
        manifest-description (get manifest "description")
        manifest-run-at (get manifest "run-at")
        manifest-inject (get manifest "inject")
        manifest-match (get manifest "auto-run-match")
        manifest-has-auto-run-key? (when has-manifest?
                                     (some #(= % "epupp/auto-run-match")
                                           (get manifest "found-keys")))
        match (cond
                manifest-has-auto-run-key?
                (normalize-match-patterns manifest-match)
                has-manifest?
                []
                :else
                (:script/match script))
        inject (cond
                 (not has-manifest?) (:script/inject script)
                 (nil? manifest-inject) []
                 (vector? manifest-inject) (vec manifest-inject)
                 (js/Array.isArray manifest-inject) (vec manifest-inject)
                 (string? manifest-inject) [manifest-inject]
                 :else [])]
    (cond-> script
      has-manifest? (assoc :script/match match
                           :script/run-at (normalize-run-at manifest-run-at)
                           :script/inject inject)
      (some? manifest-name) (assoc :script/name manifest-name)
      (and has-manifest? (some? manifest-description)) (assoc :script/description manifest-description)
      (and has-manifest? (nil? manifest-description)) (dissoc :script/description))))

(defn parse-scripts
  "Convert JS scripts array to Clojure with namespaced keys"
  ([js-scripts] (parse-scripts js-scripts {}))
  ([js-scripts {:keys [extract-manifest]}]
   (->> (js-arr->vec js-scripts)
        (mapv (fn [s]
                (let [script {:script/id (.-id s)
                              :script/name (.-name s)
                              :script/description (.-description s)
                              :script/match (js-arr->vec (.-match s))
                              :script/code (.-code s)
                              :script/enabled (.-enabled s)
                              :script/created (.-created s)
                              :script/modified (.-modified s)
                              :script/run-at (normalize-run-at (.-runAt s))
                              :script/inject (js-arr->vec (.-inject s))
                              :script/builtin? (boolean (.-builtin s))}
                      manifest (when (and extract-manifest (:script/code script))
                                 (try (extract-manifest (:script/code script))
                                      (catch :default _ nil)))]
                  (if extract-manifest
                    (derive-script-fields script manifest)
                    script)))))))

(defn script->js
  "Convert script map to JS object with simple keys for storage"
  [script]
  #js {:id (:script/id script)
       :code (:script/code script)
       :enabled (:script/enabled script)
       :created (:script/created script)
       :modified (:script/modified script)
       :builtin (:script/builtin? script)})

;; ============================================================
;; URL pattern matching
;; ============================================================

(defn script->panel-js
  "Convert script map to JS object for panel save/rename messages."
  [script]
  #js {:id (:script/id script)
       :name (:script/name script)
       :description (:script/description script)
       :match (clj->js (or (:script/match script) []))
       :code (:script/code script)
       :enabled (:script/enabled script)
       :runAt (:script/run-at script)
       :inject (clj->js (or (:script/inject script) []))
       :force (:script/force? script)})

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
  "Check if a URL matches a single pattern.
   Returns false for invalid (non-string) patterns."
  [url pattern]
  (if (string? pattern)
    (let [regex (pattern->regex pattern)]
      (.test regex url))
    false))

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

(defn get-required-origins
  "Extract unique origin patterns from a list of scripts.
   Used to determine which permissions need to be requested."
  [scripts]
  (->> scripts
       (mapcat :script/match)
       distinct
       vec))

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
;; Built-in script detection
;; ============================================================

(defn validate-script-name
  "Validate script names for reserved namespace and path traversal.
   Returns nil when valid, or a string error message when invalid."
  [input-name]
  (cond
    (nil? input-name) nil
    (not (string? input-name)) "Script name must be a string"
    (.startsWith input-name "epupp/") "Cannot create scripts in reserved namespace: epupp/"
    (.startsWith input-name "/") "Script name cannot start with '/'"
    (or (.includes input-name "./") (.includes input-name "../")) "Script name cannot contain './' or '../'"
    :else nil))

(defn builtin-script?
  "Check if a script is a built-in script via :script/builtin? metadata."
  [script]
  (boolean (:script/builtin? script)))

(defn name-matches-builtin?
  "Check if a normalized script name matches any builtin script's normalized name.
   Used to prevent creating scripts with names that would shadow builtins."
  [scripts script-name]
  (let [builtins (filter builtin-script? scripts)]
    (some #(= script-name (normalize-script-name (:script/name %)))
          builtins)))

;; ============================================================
;; Script ID generation
;; ============================================================

(defn filter-visible-scripts
  "Filter scripts for ls. When include-hidden? is true, includes built-ins."
  [scripts include-hidden?]
  (if include-hidden?
    scripts
    (filterv (comp not builtin-script?) scripts)))

(defn generate-script-id
  "Generate a stable, unique script ID based on timestamp.
   The ID is immutable once created - it does not change when the script is renamed."
  []
  (str "script-" (.now js/Date)))

;; ============================================================
;; URL to match pattern conversion
;; ============================================================

(defn url-to-match-pattern
  "Convert a URL to a match pattern string.
   Options:
   - :wildcard-scheme? - Use *:// instead of specific protocol (default: false)

   Examples:
   (url-to-match-pattern \"https://github.com/foo\")
   => \"https://github.com/*\"

   (url-to-match-pattern \"https://github.com/foo\" {:wildcard-scheme? true})
   => \"*://github.com/*\""
  ([url] (url-to-match-pattern url {}))
  ([url {:keys [wildcard-scheme?] :or {wildcard-scheme? false}}]
   (try
     (let [parsed (js/URL. url)
           scheme (if wildcard-scheme? "*" (.-protocol parsed))
           sep (if wildcard-scheme? "://" "//")]
       (str scheme sep (.-hostname parsed) "/*"))
     (catch :default _ nil))))

;; ============================================================
;; Debug: Expose for console testing
;; ============================================================

(defn diff-scripts
  "Detect changes between old and new script lists.
   Returns {:added [names], :modified [names], :removed [names]}
   where modified means the script code changed."
  [old-scripts new-scripts]
  (let [old-by-name (into {} (map (juxt :script/name identity) old-scripts))
        new-by-name (into {} (map (juxt :script/name identity) new-scripts))
        old-names (set (keys old-by-name))
        new-names (set (keys new-by-name))
        added (filterv #(not (contains? old-names %)) new-names)
        removed (filterv #(not (contains? new-names %)) old-names)
        common (filterv #(contains? old-names %) new-names)
        modified (filterv (fn [name]
                            (not= (:script/code (get old-by-name name))
                                  (:script/code (get new-by-name name))))
                          common)]
    {:added added
     :modified modified
     :removed removed}))

(set! js/globalThis.scriptUtils
      #js {:parse_scripts parse-scripts
           :script__GT_js script->js
           :pattern__GT_regex pattern->regex
           :url_matches_pattern_QMARK_ url-matches-pattern?
           :url_matches_any_pattern_QMARK_ url-matches-any-pattern?
           :get_matching_pattern get-matching-pattern
           :get_required_origins get-required-origins
           :builtin_script_QMARK_ builtin-script?
           :name_matches_builtin_QMARK_ name-matches-builtin?
           :filter_visible_scripts filter-visible-scripts
           :generate_script_id generate-script-id
           :normalize_script_name normalize-script-name
           :normalize_match_patterns normalize-match-patterns
           :normalize_run_at normalize-run-at
           :valid_run_at_values valid-run-at-values
           :default_run_at default-run-at
           :url_to_match_pattern url-to-match-pattern
           :diff_scripts diff-scripts})



;; ============================================================
;; Script change detection
;; ============================================================


