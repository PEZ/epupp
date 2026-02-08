(ns background-utils
  "Pure utility functions for background service worker.
   Extracted for testability - these have no side effects.")

(defn any-tab-connected?
  "Check if any tab has an active REPL connection.
   Pure function - takes icon-states map directly."
  [icon-states]
  (boolean (some #(= % "connected") (vals icon-states))))

(defn compute-display-icon-state
  "Compute icon state to display for the active tab.
   Shows connected (gold) only when the active tab has a REPL connection.
   Shows disconnected (white) otherwise.

   Pure function - takes icon-states map and active-tab-id."
  [icon-states active-tab-id]
  (if (= "connected" (get icon-states active-tab-id))
    "connected"
    "disconnected"))

(defn get-icon-paths
  "Get icon paths for a given state.
   State can be keyword or string - in Squint they're equivalent.
   Only supports 'connected' and 'disconnected' states."
  [state]
  (let [suffix (case state
                 "connected" "connected"
                 "disconnected")]
    #js {:16 (str "icons/icon-" suffix "-16.png")
         :32 (str "icons/icon-" suffix "-32.png")
         :48 (str "icons/icon-" suffix "-48.png")
         :128 (str "icons/icon-" suffix "-128.png")}))



(defn url-origin-allowed?
  "Check if a URL starts with any allowed origin prefix.
   Pure function - takes url and list of allowed origins."
  [url allowed-origins]
  (boolean (some #(.startsWith url %) allowed-origins)))

(defn find-tab-on-port
  "Find the first tab-id connected to a given port, excluding exclude-tab-id.
   Returns tab-id or nil."
  [connections port exclude-tab-id]
  (->> connections
       (some (fn [[tab-id conn-info]]
               (when (and (not= tab-id exclude-tab-id)
                          (= (:ws/port conn-info) port))
                 tab-id)))))

(defn connections->display-list
  "Transform connections map to list for popup display.
   Returns [{:tab-id n :port n :title s :favicon s :url s}]"
  [connections]
  (->> connections
       (mapv (fn [[tab-id conn-info]]
               {:tab-id tab-id
                :port (:ws/port conn-info)
                :title (or (:ws/tab-title conn-info) "Unknown")
                :favicon (:ws/tab-favicon conn-info)
                :url (:ws/tab-url conn-info)}))
       (sort-by :port)))

(defn tab-in-history?
  "Check if a tab-id is in the connected tabs history.
   Pure function - takes history map and tab-id."
  [connected-tabs-history tab-id]
  (contains? connected-tabs-history tab-id))

(defn get-history-port
  "Get the saved WebSocket port for a tab from history.
   Pure function - takes history map and tab-id."
  [connected-tabs-history tab-id]
  (get-in connected-tabs-history [tab-id :port]))

(defn decide-auto-connection
  "Decide whether to auto-connect based on gathered context.
   Pure function for the 'decide' phase of gather-then-decide pattern.

   Context shape:
   {:nav/auto-connect-enabled? bool   ; auto-connect to ALL pages
    :nav/auto-reconnect-enabled? bool ; reconnect previously connected tabs
    :nav/in-history? bool             ; tab was previously connected
    :nav/history-port string-or-nil   ; port from history
    :nav/saved-port string}           ; port from storage (for auto-connect-all)

   Returns decision map:
   {:decision :connect-all|:reconnect|:none
    :port string-or-nil}"
  [{:nav/keys [auto-connect-enabled? auto-reconnect-enabled?
               in-history? history-port saved-port]}]
  (cond
    ;; Auto-connect-all supersedes everything
    auto-connect-enabled?
    {:decision "connect-all"
     :port saved-port}

    ;; Auto-reconnect for previously connected tabs only
    (and auto-reconnect-enabled? in-history? history-port)
    {:decision "reconnect"
     :port history-port}

    ;; No automatic connection
    :else
    {:decision "none"}))

(defn sponsor-url-matches?
  "Verify that a tab URL is on the expected GitHub sponsors page.
   Returns true when tab-url starts with https://github.com/sponsors/{username}."
  [tab-url username]
  (boolean
   (and (string? tab-url)
        (string? username)
        (not (empty? username))
        (.startsWith tab-url (str "https://github.com/sponsors/" username)))))
