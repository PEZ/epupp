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
                (let [script (cond-> {:script/id (.-id s)
                                      :script/code (.-code s)
                                      :script/enabled (.-enabled s)
                                      :script/created (.-created s)
                                      :script/modified (.-modified s)
                                      :script/builtin? (boolean (.-builtin s))
                                      :script/always-enabled? (boolean (.-alwaysEnabled s))}
                              (.-special s) (assoc :script/special? true)
                              (.-webInstallerScan s) (assoc :script/web-installer-scan true))
                      manifest (when (and extract-manifest (:script/code script))
                                 (try (extract-manifest (:script/code script))
                                      (catch :default _ nil)))]
                  (if extract-manifest
                    (derive-script-fields script manifest)
                    script)))))))

(defn script->js
  "Convert script map to JS object with simple keys for storage.
   Includes runAt and match for early injection loader.
   Other derived fields (name, description, inject) are not stored
   since they are re-derived from the manifest on load."
  [script]
  #js {:id (:script/id script)
       :code (:script/code script)
       :enabled (:script/enabled script)
       :created (:script/created script)
       :modified (:script/modified script)
       :builtin (:script/builtin? script)
       :alwaysEnabled (:script/always-enabled? script)
       :special (:script/special? script)
       :webInstallerScan (:script/web-installer-scan script)
       :runAt (:script/run-at script)
       :match (clj->js (or (:script/match script) []))})

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
    (.startsWith (.toLowerCase input-name) "epupp/") "Cannot create scripts in reserved namespace: epupp/"
    (.startsWith input-name "/") "Script name cannot start with '/'"
    (or (.includes input-name "./") (.includes input-name "../")) "Script name cannot contain './' or '../'"
    :else nil))

(defn normalize-and-merge-script
  "Pure script normalization and merge. No persistence, no atoms.
   Returns {:script updated-script} or {:error error-message}.

   Handles: name extraction from manifest, name validation/normalization,
   manifest-derived fields (via derive-script-fields), enabled-state
   computation, existing-script merge, and timestamps.

   Each caller is responsible for:
   - Manifest extraction
   - ID resolution
   - Error handling strategy (throw vs error map)
   - Persistence"
  [script existing manifest {:keys [is-builtin? now-iso]}]
  (let [has-manifest? (some? manifest)
        manifest-name (when has-manifest?
                        (or (get manifest "raw-script-name")
                            (get manifest "script-name")))
        raw-name (or (and has-manifest? manifest-name)
                     (:script/name script))
        name-error (when (and raw-name (not is-builtin?))
                     (validate-script-name raw-name))
        normalized-name (when raw-name
                          (if is-builtin?
                            raw-name
                            (normalize-script-name raw-name)))]
    (if name-error
      {:error name-error}
      (let [named-script (cond-> script
                           normalized-name (assoc :script/name normalized-name))
            ;; When no manifest and no match on incoming, fall back to existing
            with-match-fallback (if (and (not has-manifest?)
                                        (nil? (:script/match named-script))
                                        existing)
                                  (assoc named-script :script/match (:script/match existing))
                                  named-script)
            ;; Apply manifest-derived fields (match, run-at, description, inject)
            derived (derive-script-fields with-match-fallback manifest)
            ;; derive-script-fields may set raw manifest name; override with normalized
            derived (if normalized-name
                      (assoc derived :script/name normalized-name)
                      derived)
            ;; Compute enabled state
            has-auto-run? (seq (:script/match derived))
            is-update? (some? existing)
            new-enabled (cond
                          (:script/always-enabled? script) true
                          (not has-auto-run?) false
                          is-update? (:script/enabled existing)
                          (some? (:script/enabled derived)) (:script/enabled derived)
                          :else false)
            ;; Merge with existing or create new
            merged (if is-update?
                     (-> (merge existing (dissoc derived :script/enabled))
                         (assoc :script/enabled new-enabled))
                     (assoc derived :script/enabled new-enabled))
            ;; Add timestamps
            timestamped (if is-update?
                          (assoc merged :script/modified now-iso)
                          (assoc merged
                                 :script/created now-iso
                                 :script/modified now-iso))]
        {:script timestamped}))))

(defn builtin-script?
  "Check if a script is a built-in script via :script/builtin? metadata."
  [script]
  (boolean (:script/builtin? script)))

(defn special-script?
  "Check if a script is a special script via :script/special? flag.
   Special scripts appear in a dedicated 'Special' section in the popup."
  [script]
  (boolean (:script/special? script)))

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

(defn detect-name-conflict
  "Detect if saving with new-name would conflict with existing scripts.
   Returns the conflicting script if conflict exists, nil otherwise.

   A conflict exists when:
   - A script with the normalized new-name exists in scripts-list
   - AND the normalized new-name differs from the original-name

   This allows editing a script without changing its name (no conflict),
   but prevents creating a new script or renaming to an existing name.

   Args:
   - scripts-list: vector of script maps with :script/name
   - new-name: the desired name (can be unnormalized, e.g., 'My Script')
   - original-name: current script's normalized name (nil for new scripts)"
  [scripts-list new-name original-name]
  (if (nil? new-name)
    nil
    (let [normalized-name (normalize-script-name new-name)
          ;; Find existing script with matching normalized name
          existing-script (some #(when (= (normalize-script-name (:script/name %)) normalized-name) %)
                                scripts-list)]
      ;; Conflict if existing script found AND we're not just keeping the same name
      (if (and existing-script
               (not= normalized-name original-name))
        existing-script
        nil))))


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

;; ============================================================
;; Page scriptability detection
;; ============================================================

(def blocked-schemes
  "URL schemes that block extension content script injection across all browsers."
  ["chrome:" "chrome-extension:" "chrome-search:" "chrome-untrusted:"
   "edge:" "brave:" "opera:" "vivaldi:" "arc:"
   "about:" "moz-extension:" "safari-web-extension:"
   "devtools:" "view-source:"])

(def blocked-domains-by-browser
  "HTTPS domains blocked per browser. Keys are browser type keywords,
   values are vectors of domain prefixes to match against the full URL."
  {:chrome  ["https://chrome.google.com/webstore"
             "https://chromewebstore.google.com"]
   :brave   ["https://chrome.google.com/webstore"
             "https://chromewebstore.google.com"]
   :edge    ["https://chrome.google.com/webstore"
             "https://chromewebstore.google.com"
             "https://microsoftedge.microsoft.com/addons"]
   :firefox ["https://addons.mozilla.org"]})

(defn detect-browser-type
  "Detect the browser type from the runtime environment.
   Returns :firefox, :brave, :edge, :safari, or :chrome.
   Checks in priority order: Firefox first since it has chrome compat layer."
  []
  (let [ua (.-userAgent js/navigator)]
    (cond
      (.includes ua "Firefox") :firefox
      (some? (.-brave js/navigator)) :brave
      (.includes ua "Edg/") :edge
      (and (.includes ua "Safari")
           (not (.includes ua "Chrome"))) :safari
      :else :chrome)))

(defn check-page-scriptability
  "Check if a page URL is scriptable by the extension.
   Pure function taking URL string and browser type keyword.
   Returns map with :scriptable? boolean and :message string (when not scriptable).

   Three blocking conditions checked in order:
   1. nil/empty URL
   2. Blocked URL schemes (e.g. chrome:, about:, devtools:)
   3. Browser-specific blocked domains (e.g. extension stores)"
  [url browser-type]
  (cond
    (or (nil? url) (= url ""))
    {:scriptable? false
     :message "No URL available for this tab"}

    (some #(.startsWith url %) blocked-schemes)
    {:scriptable? false
     :message (str "Unfortunatelly neither the REPL nor Userscripting works on " (first (.split url ":")) ": pages.")}

    (some #(.startsWith url %) (get blocked-domains-by-browser browser-type []))
    {:scriptable? false
     :message "Unfortunatelly neither the REPL nor Userscripting works on Extension/Addon stores."}

    :else
    {:scriptable? true}))
