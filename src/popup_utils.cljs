(ns popup-utils
  "Pure utility functions for the popup UI.
   No browser dependencies - takes config/state as arguments.")

;; ============================================================
;; Status display
;; ============================================================

(defn status-class
  "Map status string to CSS class for styling"
  [status]
  (when status
    (cond
      (or (.startsWith status "Failed") (.startsWith status "Error")) "status status-failed"
      (or (.endsWith status "...") (.includes status "not connected")) "status status-pending"
      :else "status")))

(defn status-type
  "Map status string to semantic type for status-text component.
   Returns :error, :success, or nil for neutral status."
  [status]
  (when status
    (cond
      (or (.startsWith status "Failed") (.startsWith status "Error")) :error
      (or (.endsWith status "...") (.includes status "not connected")) nil ; pending - no special color
      (.startsWith status "Connected") :success
      :else nil)))

(defn generate-server-cmd
  "Generate the bb browser-nrepl server command.
   Takes deps-string (from config) and port settings."
  [{:keys [deps-string nrepl-port ws-port]}]
  (str "bb -Sdeps '" deps-string "' "
       "-e '(require (quote [sci.nrepl.browser-server :as server])) "
       "(server/start! {:nrepl-port " nrepl-port " :websocket-port " ws-port "}) "
       "@(promise)'"))

;; ============================================================
;; Script list transformations
;; ============================================================

(defn toggle-script-in-list
  "Toggle enabled state for a script."
  [scripts script-id]
  (mapv (fn [s]
          (if (= (:script/id s) script-id)
            (assoc s :script/enabled (not (:script/enabled s)))
            s))
        scripts))

(defn remove-script-from-list
  "Remove script from list by id."
  [scripts script-id]
  (filterv #(not= (:script/id %) script-id) scripts))

;; ============================================================
;; Script sorting for display
;; ============================================================

(defn sort-scripts-for-display
  "Sort scripts for UI display: user scripts alphabetically first,
   then built-in scripts alphabetically.
   Uses script name for alphabetic ordering (case-insensitive)."
  [scripts builtin-script?-fn]
  (let [user-scripts (filterv (comp not builtin-script?-fn) scripts)
        builtin-scripts (filterv builtin-script?-fn scripts)
        sort-by-name #(vec (sort-by (fn [s] (.toLowerCase (:script/name s))) %))]
    (concat (sort-by-name user-scripts)
            (sort-by-name builtin-scripts))))
