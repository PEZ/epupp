(ns popup-actions
  "Pure action handlers for the extension popup.
   No browser dependencies - testable without Chrome APIs."
  (:require [popup-utils :as popup-utils]))

(defn handle-action
  "Pure action handler for popup state transitions.
   Returns map with :uf/db, :uf/fxs, :uf/dxs keys.

   uf-data should contain:
   - :system/now - current timestamp
   - :config/deps-string - deps string for server command generation
   - :config/allowed-origins - default allowed origins from config"
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
        {:uf/fxs [[:popup/fx.connect port]]}))

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
    ;; Simple delete - shadow watcher handles animation
    (let [[script-id] args
          scripts (:scripts/list state)
          updated (filterv (fn [s] (not= (:script/id s) script-id)) scripts)]
      {:uf/db (assoc state :scripts/list updated)
       :uf/fxs [[:popup/fx.delete-script updated script-id]]})

    :popup/ax.load-current-url
    {:uf/fxs [[:popup/fx.load-current-url]]}

    :popup/ax.inspect-script
    (let [[script-id] args
          script (some #(when (= (:script/id %) script-id) %) (:scripts/list state))]
      (when script
        {:uf/fxs [[:popup/fx.inspect-script script]
                  [:uf/fx.defer-dispatch [[:popup/ax.show-system-banner "info" "Open the Epupp panel in Developer Tools"]] 0]]}))

    :popup/ax.evaluate-script
    (let [[script-id] args
          script (some #(when (= (:script/id %) script-id) %) (:scripts/list state))]
      (when script
        {:uf/fxs [[:popup/fx.evaluate-script script]]}))

    :popup/ax.load-user-origins
    {:uf/fxs [[:popup/fx.load-user-origins]]}

    :popup/ax.set-new-origin
    (let [[value] args]
      {:uf/db (assoc state :settings/new-origin value)})

    :popup/ax.add-origin
    (let [origin (.trim (:settings/new-origin state))
          default-origins (:settings/default-origins state)
          user-origins (:settings/user-origins state)]
      (cond
        (not (popup-utils/valid-origin? origin))
        {:uf/dxs [[:popup/ax.show-system-banner "error" "Must start with http:// or https:// and end with / or :" {}]]}

        (popup-utils/origin-already-exists? origin default-origins user-origins)
        {:uf/dxs [[:popup/ax.show-system-banner "error" "Origin already exists" {}]]}

        :else
        {:uf/db (-> state
                    (update :settings/user-origins conj origin)
                    (assoc :settings/new-origin ""))
         :uf/fxs [[:popup/fx.add-user-origin origin]]}))

    :popup/ax.remove-origin
    ;; Simple remove - shadow watcher handles animation
    (let [[origin] args]
      {:uf/db (update state :settings/user-origins (fn [origins] (filterv (fn [o] (not= o origin)) origins)))
       :uf/fxs [[:popup/fx.remove-user-origin origin]]})

    :popup/ax.load-auto-connect-setting
    {:uf/fxs [[:popup/fx.load-auto-connect-setting]]}

    :popup/ax.toggle-auto-connect-repl
    (let [new-value (not (:settings/auto-connect-repl state))]
      {:uf/db (assoc state :settings/auto-connect-repl new-value)
       :uf/fxs [[:popup/fx.save-auto-connect-setting new-value]]})

    :popup/ax.load-auto-reconnect-setting
    {:uf/fxs [[:popup/fx.load-auto-reconnect-setting]]}

    :popup/ax.toggle-auto-reconnect-repl
    (let [new-value (not (:settings/auto-reconnect-repl state))]
      {:uf/db (assoc state :settings/auto-reconnect-repl new-value)
       :uf/fxs [[:popup/fx.save-auto-reconnect-setting new-value]]})

    :popup/ax.load-fs-sync-setting
    {:uf/fxs [[:popup/fx.load-fs-sync-setting]]}

    :popup/ax.toggle-fs-sync
    (let [new-value (not (:settings/fs-repl-sync-enabled state))]
      {:uf/db (assoc state :settings/fs-repl-sync-enabled new-value)
       :uf/fxs [[:popup/fx.save-fs-sync-setting new-value]]})

    :popup/ax.toggle-section
    (let [[section-id] args]
      {:uf/db (update-in state [:ui/sections-collapsed section-id] not)})

    :popup/ax.export-scripts
    {:uf/fxs [[:popup/fx.export-scripts]]}

    :popup/ax.import-scripts
    {:uf/fxs [[:popup/fx.trigger-import]]}

    :popup/ax.handle-import
    (let [[scripts-data] args]
      {:uf/fxs [[:popup/fx.import-scripts scripts-data]]})

    :popup/ax.dump-dev-log
    {:uf/fxs [[:popup/fx.dump-dev-log]]}

    :popup/ax.load-connections
    {:uf/fxs [[:popup/fx.load-connections]]}

    :popup/ax.reveal-script
    (let [[script-name] args
          new-state (-> state
                        ;; Expand script sections so reveal has a chance to find it
                        (assoc-in [:ui/sections-collapsed :matching-scripts] false)
                        (assoc-in [:ui/sections-collapsed :other-scripts] false)
                        (assoc :ui/reveal-highlight-script-name script-name))]
      {:uf/db new-state
       :uf/fxs [[:popup/fx.reveal-script script-name]
                [:uf/fx.defer-dispatch [[:db/ax.assoc :ui/reveal-highlight-script-name nil]] 2000]]})

    :popup/ax.reveal-tab
    (let [[tab-id] args]
      {:uf/fxs [[:popup/fx.reveal-tab tab-id]]})

    :popup/ax.disconnect-tab
    ;; Simple disconnect - shadow watcher handles animation
    (let [[tab-id] args]
      {:uf/fxs [[:popup/fx.disconnect-tab tab-id]]})

    :popup/ax.mark-scripts-modified
    (let [[script-names] args
          current-modified (or (:ui/recently-modified-scripts state) #{})
          new-modified (into current-modified script-names)]
      {:uf/db (assoc state :ui/recently-modified-scripts new-modified)
       :uf/fxs [[:uf/fx.defer-dispatch [[:popup/ax.clear-modified-scripts]] 2000]]})

    :popup/ax.clear-modified-scripts
    {:uf/db (assoc state :ui/recently-modified-scripts #{})}

    ;; System banner actions - multi-message support
    ;; Each banner has {:id :type :message :category :leaving} and expires independently
    ;; Optional 4th arg :category - banners with same category replace each other
    :popup/ax.show-system-banner
    (let [[event-type message bulk-info category] args
          {:keys [bulk-op? bulk-final? bulk-names]} bulk-info
          banner-id (str "msg-" (:system/now uf-data) "-" (count (:ui/system-banners state)))
          new-banner (cond-> {:id banner-id :type event-type :message message}
                       category (assoc :category category))
          banners (or (:ui/system-banners state) [])
          ;; If category provided, filter out existing banners with same category
          banners (if category
                    (filterv #(not= (:category %) category) banners)
                    banners)]
      {:uf/db (assoc state :ui/system-banners (conj banners new-banner))
       :uf/fxs [[:popup/fx.log-system-banner message bulk-op? bulk-final? bulk-names]
                [:uf/fx.defer-dispatch [[:popup/ax.clear-system-banner banner-id]] 2000]]})

    :popup/ax.clear-system-banner
    (let [[banner-id] args
          banners (or (:ui/system-banners state) [])
          target-banner (some #(when (= (:id %) banner-id) %) banners)]
      (if (and target-banner (:leaving target-banner))
        ;; Step 2: After animation, remove the banner
        {:uf/db (assoc state :ui/system-banners (filterv #(not= (:id %) banner-id) banners))}
        ;; Step 1: Mark as leaving, defer actual removal
        {:uf/db (assoc state :ui/system-banners
                       (mapv #(if (= (:id %) banner-id)
                                (assoc % :leaving true)
                                %)
                             banners))
         :uf/fxs [[:uf/fx.defer-dispatch [[:popup/ax.clear-system-banner banner-id]] 250]]}))

    :popup/ax.track-bulk-name
    (let [[bulk-id script-name] args]
      {:uf/db (update-in state [:ui/system-bulk-names bulk-id] (fnil conj []) script-name)})

    :popup/ax.clear-bulk-names
    (let [[bulk-id] args]
      {:uf/db (update state :ui/system-bulk-names dissoc bulk-id)})

    ;; Shadow list sync handlers (for animations)
    ;; These receive {:added-items [...] :removed-ids #{...}} from list watchers
    :ui/ax.sync-scripts-shadow
    (let [[{:keys [added-items removed-ids]}] args
          shadow (:ui/scripts-shadow state)
          source-list (:scripts/list state)
          ;; Build map of source items by ID for quick lookup
          source-by-id (into {} (map (fn [s] [(:script/id s) s]) source-list))
          ;; Mark removed items as leaving, update existing items' content
          shadow-with-updates (mapv (fn [s]
                                      (let [script-id (get-in s [:item :script/id])]
                                        (cond
                                          ;; Item being removed - mark as leaving
                                          (contains? removed-ids script-id)
                                          (assoc s :ui/leaving? true)
                                          ;; Item exists in source - update content
                                          (contains? source-by-id script-id)
                                          (assoc s :item (get source-by-id script-id))
                                          ;; Item not in source and not being removed - keep as is
                                          :else s)))
                                    shadow)
          ;; Add new items with entering flag
          new-shadow-items (mapv (fn [item] {:item item :ui/entering? true :ui/leaving? false}) added-items)
          updated-shadow (into shadow-with-updates new-shadow-items)
          added-ids (set (map :script/id added-items))]
      {:uf/db (assoc state :ui/scripts-shadow updated-shadow)
       :uf/fxs [[:uf/fx.defer-dispatch [[:ui/ax.clear-entering-scripts added-ids]] 50]
                [:uf/fx.defer-dispatch [[:ui/ax.remove-leaving-scripts removed-ids]] 250]]})

    :ui/ax.clear-entering-scripts
    (let [[ids] args]
      {:uf/db (update state :ui/scripts-shadow
                      (fn [shadow]
                        (mapv (fn [s]
                                (if (contains? ids (get-in s [:item :script/id]))
                                  (assoc s :ui/entering? false)
                                  s))
                              shadow)))})

    :ui/ax.remove-leaving-scripts
    (let [[ids] args]
      {:uf/db (update state :ui/scripts-shadow
                      (fn [shadow]
                        (filterv (fn [s] (not (contains? ids (get-in s [:item :script/id])))) shadow)))})

    :ui/ax.sync-connections-shadow
    (let [[{:keys [added-items removed-ids]}] args
          shadow (:ui/connections-shadow state)
          source-list (:repl/connections state)
          ;; Build map of source items by ID for quick lookup
          source-by-id (into {} (map (fn [c] [(:tab-id c) c]) source-list))
          ;; Mark removed items as leaving, update existing items' content
          shadow-with-updates (mapv (fn [s]
                                      (let [tab-id (:tab-id (:item s))]
                                        (cond
                                          ;; Item being removed - mark as leaving
                                          (contains? removed-ids tab-id)
                                          (assoc s :ui/leaving? true)
                                          ;; Item exists in source - update content
                                          (contains? source-by-id tab-id)
                                          (assoc s :item (get source-by-id tab-id))
                                          ;; Item not in source and not being removed - keep as is
                                          :else s)))
                                    shadow)
          ;; Add new items with entering flag
          new-shadow-items (mapv (fn [item] {:item item :ui/entering? true :ui/leaving? false}) added-items)
          updated-shadow (into shadow-with-updates new-shadow-items)
          added-ids (set (map :tab-id added-items))]
      {:uf/db (assoc state :ui/connections-shadow updated-shadow)
       :uf/fxs [[:uf/fx.defer-dispatch [[:ui/ax.clear-entering-tabs added-ids]] 50]
                [:uf/fx.defer-dispatch [[:ui/ax.remove-leaving-tabs removed-ids]] 250]]})

    :ui/ax.clear-entering-tabs
    (let [[ids] args]
      {:uf/db (update state :ui/connections-shadow
                      (fn [shadow]
                        (mapv (fn [s]
                                (if (contains? ids (:tab-id (:item s)))
                                  (assoc s :ui/entering? false)
                                  s))
                              shadow)))})

    :ui/ax.remove-leaving-tabs
    (let [[ids] args]
      {:uf/db (update state :ui/connections-shadow
                      (fn [shadow]
                        (filterv (fn [s] (not (contains? ids (:tab-id (:item s))))) shadow)))})

    :ui/ax.sync-origins-shadow
    (let [[{:keys [added-items removed-ids]}] args
          shadow (:ui/origins-shadow state)
          source-list (:settings/user-origins state)
          ;; Build set of source items for quick lookup (origins are strings)
          source-set (set source-list)
          ;; Mark removed items as leaving, update existing items' content (for origins, item IS the origin string)
          shadow-with-updates (mapv (fn [s]
                                      (let [origin (:item s)]
                                        (cond
                                          ;; Item being removed - mark as leaving
                                          (contains? removed-ids origin)
                                          (assoc s :ui/leaving? true)
                                          ;; Item exists in source - content already correct for origins
                                          (contains? source-set origin)
                                          s
                                          ;; Item not in source and not being removed - keep as is
                                          :else s)))
                                    shadow)
          ;; Add new items with entering flag
          new-shadow-items (mapv (fn [item] {:item item :ui/entering? true :ui/leaving? false}) added-items)
          updated-shadow (into shadow-with-updates new-shadow-items)]
      {:uf/db (assoc state :ui/origins-shadow updated-shadow)
       :uf/fxs [[:uf/fx.defer-dispatch [[:ui/ax.clear-entering-origins (set added-items)]] 50]
                [:uf/fx.defer-dispatch [[:ui/ax.remove-leaving-origins removed-ids]] 250]]})

    :ui/ax.clear-entering-origins
    (let [[ids] args]
      {:uf/db (update state :ui/origins-shadow
                      (fn [shadow]
                        (mapv (fn [s]
                                (if (contains? ids (:item s))
                                  (assoc s :ui/entering? false)
                                  s))
                              shadow)))})

    :ui/ax.remove-leaving-origins
    (let [[ids] args]
      {:uf/db (update state :ui/origins-shadow
                      (fn [shadow]
                        (filterv (fn [s] (not (contains? ids (:item s)))) shadow)))})

    :uf/unhandled-ax))