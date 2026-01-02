(ns url-matching
  "URL pattern matching for userscripts.
   Supports TamperMonkey/Greasemonkey-style match patterns.

   Pattern syntax:
   - *://example.com/* - matches any scheme (http/https)
   - https://*.example.com/* - matches any subdomain
   - https://example.com/path/* - matches path prefix
   - <all_urls> - matches all URLs

   Note: * matches any characters, ? is literal (not a wildcard)."
  (:require [storage :as storage]))

;; ============================================================
;; Pattern to Regex conversion
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

;; ============================================================
;; URL matching
;; ============================================================

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
;; Script filtering
;; ============================================================

(defn get-matching-scripts
  "Get all enabled scripts that match the given URL"
  [url]
  (->> (storage/get-scripts)
       (filter :script/enabled)
       (filter #(url-matches-any-pattern? url (:script/match %)))
       vec))

(defn get-matching-pattern
  "Find which pattern in a script matches the given URL"
  [url script]
  (->> (:script/match script)
       (filter #(url-matches-pattern? url %))
       first))

(defn get-required-origins
  "Extract unique origin patterns from a list of scripts.
   Used to determine which permissions need to be requested."
  [scripts]
  (->> scripts
       (mapcat :script/match)
       distinct
       vec))

;; ============================================================
;; Debug: Expose for console testing
;; ============================================================

(set! js/globalThis.urlMatching
      #js {:pattern_to_regex pattern->regex
           :url_matches_pattern_QMARK_ url-matches-pattern?
           :url_matches_any_pattern_QMARK_ url-matches-any-pattern?
           :get_matching_scripts get-matching-scripts
           :get_matching_pattern get-matching-pattern
           :get_required_origins get-required-origins})
