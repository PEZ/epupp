(ns url-matching
  "URL pattern matching for userscripts.
   Supports TamperMonkey/Greasemonkey-style match patterns.

   Pattern syntax:
   - *://example.com/* - matches any scheme (http/https)
   - https://*.example.com/* - matches any subdomain
   - https://example.com/path/* - matches path prefix
   - <all_urls> - matches all URLs

   Note: * matches any characters, ? is literal (not a wildcard).

   Most URL matching functions are in script-utils.cljs for sharing.
   This module adds storage-dependent filtering functions."
  (:require [storage :as storage]
            [script-utils :as script-utils]))

;; Re-export core matching functions for backwards compatibility
(def pattern->regex script-utils/pattern->regex)
(def url-matches-pattern? script-utils/url-matches-pattern?)
(def url-matches-any-pattern? script-utils/url-matches-any-pattern?)
(def get-matching-pattern script-utils/get-matching-pattern)
(def get-required-origins script-utils/get-required-origins)

;; ============================================================
;; Storage-dependent script filtering
;; ============================================================

(defn get-matching-scripts
  "Get all enabled scripts that match the given URL"
  [url]
  (->> (storage/get-scripts)
       (filter :script/enabled)
       (filter #(url-matches-any-pattern? url (:script/match %)))
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
