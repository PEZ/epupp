(ns background-utils
  "Pure utility functions for background service worker.
   Extracted for testability - these have no side effects.")

(defn any-tab-connected?
  "Check if any tab has an active REPL connection.
   Pure function - takes icon-states map directly."
  [icon-states]
  (boolean (some #(= % "connected") (vals icon-states))))

(defn compute-display-icon-state
  "Compute icon state to display based on:
   - Connected is GLOBAL: if ANY tab has REPL connected -> green
   - Injected is TAB-LOCAL: only if active-tab has Scittle -> yellow
   - Otherwise: disconnected (white)

   Pure function - takes icon-states map and active-tab-id."
  [icon-states active-tab-id]
  (let [tab-state (get icon-states active-tab-id)]
    (cond
      ;; Global: any tab connected -> green
      (any-tab-connected? icon-states) "connected"
      ;; Tab-local: active tab injected -> yellow
      (= "injected" tab-state) "injected"
      ;; Default: disconnected
      :else "disconnected")))

(defn get-icon-paths
  "Get icon paths for a given state.
   State can be keyword or string - in Squint they're equivalent."
  [state]
  (let [suffix (case state
                 "connected" "connected"
                 "injected" "injected"
                 "disconnected")]
    #js {:16 (str "icons/icon-" suffix "-16.png")
         :32 (str "icons/icon-" suffix "-32.png")
         :48 (str "icons/icon-" suffix "-48.png")
         :128 (str "icons/icon-" suffix "-128.png")}))

(defn count-pending-for-url
  "Count scripts needing approval for a given URL.
   A script needs approval if: enabled, matches URL, and pattern not yet approved.

   Pure function - takes url, scripts list, and functions to check matching/approval."
  [url scripts get-matching-pattern-fn pattern-approved?-fn]
  (if (or (nil? url) (= "" url))
    0
    (->> scripts
         (filter (fn [script]
                   (when-let [pattern (get-matching-pattern-fn url script)]
                     (not (pattern-approved?-fn script pattern)))))
         count)))

(defn url-origin-allowed?
  "Check if a URL starts with any allowed origin prefix.
   Pure function - takes url and list of allowed origins."
  [url allowed-origins]
  (boolean (some #(.startsWith url %) allowed-origins)))
