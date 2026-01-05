(ns popup-actions
  "Pure action handlers for the extension popup.
   No browser dependencies - testable without Chrome APIs."
  (:require [popup-utils :as popup-utils]))

(defn handle-action
  "Pure action handler for popup state transitions.
   Returns map with :uf/db, :uf/fxs, :uf/dxs keys.
   
   uf-data should contain:
   - :system/now - current timestamp
   - :config/deps-string - deps string for server command generation"
  [state uf-data [action & args]]
  (case action
    :popup/ax.set-nrepl-port
    (let [[port] args
          new-state (assoc state :ports/nrepl port)]
      {:uf/db new-state
       :uf/fxs [[:popup/fx.save-ports (select-keys new-state [:ports/nrepl :ports/ws])]]})

    :popup/ax.set-ws-port
    (let [[port] args
          new-state (assoc state :ports/ws port)]
      {:uf/db new-state
       :uf/fxs [[:popup/fx.save-ports (select-keys new-state [:ports/nrepl :ports/ws])]]})

    :popup/ax.copy-command
    (let [deps-string (:config/deps-string uf-data)
          cmd (popup-utils/generate-server-cmd
               {:deps-string deps-string
                :nrepl-port (:ports/nrepl state)
                :ws-port (:ports/ws state)})]
      {:uf/fxs [[:popup/fx.copy-command cmd]]})

    :popup/ax.connect
    (let [port (js/parseInt (:ports/ws state) 10)]
      (when (and (not (js/isNaN port)) (<= 1 port 65535))
        {:uf/db (assoc state :ui/status "Checking...")
         :uf/fxs [[:popup/fx.connect port]]}))

    :popup/ax.check-status
    {:uf/fxs [[:popup/fx.check-status (:ports/ws state)]]}

    :popup/ax.load-saved-ports
    {:uf/fxs [[:popup/fx.load-saved-ports]]}

    :popup/ax.load-scripts
    {:uf/fxs [[:popup/fx.load-scripts]]}

    :popup/ax.toggle-script
    (let [[script-id matching-pattern] args
          scripts (:scripts/list state)]
      {:uf/fxs [[:popup/fx.toggle-script scripts script-id matching-pattern]]})

    :popup/ax.delete-script
    (let [[script-id] args
          scripts (:scripts/list state)]
      {:uf/fxs [[:popup/fx.delete-script scripts script-id]]})

    :popup/ax.load-current-url
    {:uf/fxs [[:popup/fx.load-current-url]]}

    :popup/ax.approve-script
    (let [[script-id pattern] args
          scripts (:scripts/list state)]
      {:uf/fxs [[:popup/fx.approve-script scripts script-id pattern]]})

    :popup/ax.deny-script
    (let [[script-id] args
          scripts (:scripts/list state)]
      {:uf/fxs [[:popup/fx.deny-script scripts script-id]]})

    :popup/ax.edit-script
    (let [[script-id] args
          script (some #(when (= (:script/id %) script-id) %) (:scripts/list state))]
      (when script
        {:uf/db (assoc state :ui/editing-hint-script-id script-id)
         :uf/fxs [[:popup/fx.edit-script script]
                  [:uf/fx.defer-dispatch [[:db/ax.assoc :ui/editing-hint-script-id nil]] 3000]]}))

    :uf/unhandled-ax))
