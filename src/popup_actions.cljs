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
        {:uf/fxs [[:popup/fx.connect port]]}))

    :popup/ax.check-status
    {:uf/fxs [[:popup/fx.check-status (:ports/ws state)]]}

    :popup/ax.load-saved-ports
    {:uf/fxs [[:popup/fx.load-saved-ports
               (:settings/default-nrepl-port state)
               (:settings/default-ws-port state)]]}

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

    :popup/ax.set-default-nrepl-port
    (let [[port] args
          new-state (assoc state :settings/default-nrepl-port port)]
      {:uf/db new-state
       :uf/fxs [[:popup/fx.save-default-ports-setting (select-keys new-state [:settings/default-nrepl-port :settings/default-ws-port])]]})

    :popup/ax.set-default-ws-port
    (let [[port] args
          new-state (assoc state :settings/default-ws-port port)]
      {:uf/db new-state
       :uf/fxs [[:popup/fx.save-default-ports-setting (select-keys new-state [:settings/default-nrepl-port :settings/default-ws-port])]]})

    :popup/ax.load-default-ports-setting
    {:uf/fxs [[:popup/fx.load-default-ports-setting]]}

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

    :popup/ax.load-fs-sync-status
    {:uf/fxs [[:popup/fx.load-fs-sync-status]]}

    :popup/ax.toggle-fs-sync
    (let [current-tab-id (:scripts/current-tab-id state)
          currently-enabled? (and (some? current-tab-id)
                                  (= current-tab-id (:fs/sync-tab-id state)))
          new-enabled (not currently-enabled?)]
      ;; Don't optimistically update state - wait for broadcast from background
      {:uf/fxs [[:popup/fx.toggle-fs-sync current-tab-id new-enabled]]})

    :popup/ax.load-debug-logging-setting
    {:uf/fxs [[:popup/fx.load-debug-logging-setting]]}

    :popup/ax.toggle-debug-logging
    (let [new-value (not (:settings/debug-logging state))]
      {:uf/db (assoc state :settings/debug-logging new-value)
       :uf/fxs [[:popup/fx.save-debug-logging-setting new-value]]})

    :popup/ax.toggle-section
    (let [[section-id] args]
      {:uf/db (update-in state [:ui/sections-collapsed section-id] not)})

    :popup/ax.toggle-creator-menu
    {:uf/db (update state :ui/creator-menu-open? not)}

    :popup/ax.close-creator-menu
    {:uf/db (assoc state :ui/creator-menu-open? false)}

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
                        (assoc-in [:ui/sections-collapsed :manual-scripts] false)
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
    ;; Each banner has {:id :type :message :favicon :category :leaving} and expires independently
    ;; bulk-info map can contain: :bulk-op? :bulk-final? :bulk-names :favicon
    ;; Optional 4th arg :category - banners with same category replace each other
    :popup/ax.show-system-banner
    (let [[event-type message bulk-info category] args
          {:keys [bulk-op? bulk-final? bulk-names favicon]} bulk-info
          banner-id (str "msg-" (:system/now uf-data) "-" (count (:ui/system-banners state)))
          new-banner (cond-> {:id banner-id :type event-type :message message}
                       favicon (assoc :favicon favicon)
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
      (when target-banner
        (if (:leaving target-banner)
          ;; Step 2: After animation, remove the banner
          {:uf/db (assoc state :ui/system-banners (filterv #(not= (:id %) banner-id) banners))}
          ;; Step 1: Mark as leaving, defer actual removal
          {:uf/db (assoc state :ui/system-banners
                         (mapv #(if (= (:id %) banner-id)
                                  (assoc % :leaving true)
                                  %)
                               banners))
           :uf/fxs [[:uf/fx.defer-dispatch [[:popup/ax.clear-system-banner banner-id]] 250]]})))

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

    :popup/ax.set-brave-detected
    (let [[brave?] args]
      {:uf/db (assoc state :browser/brave? brave?)})

    :popup/ax.set-dev-sponsor-username
    (let [[username] args]
      {:uf/db (assoc state :sponsor/sponsored-username username)
       :uf/fxs [[:popup/fx.set-dev-sponsor-username username]]})

    :popup/ax.reset-sponsor-status
    {:uf/db (assoc state :sponsor/status false :sponsor/checked-at nil)
     :uf/fxs [[:popup/fx.reset-sponsor-status]]}

    :popup/ax.load-dev-sponsor-username
    {:uf/fxs [[:popup/fx.load-dev-sponsor-username]]}

    :popup/ax.check-sponsor
    (let [username (or (:sponsor/sponsored-username state) "PEZ")]
      {:uf/fxs [[:popup/fx.check-sponsor username]]})

    :popup/ax.load-sponsor-status
    {:uf/fxs [[:popup/fx.load-sponsor-status]]}

    :popup/ax.check-page-scriptability
    {:uf/fxs [[:popup/fx.check-page-scriptability]]}

    :popup/ax.handle-system-banner
    (let [[{:keys [event-type operation script-name error unchanged
                   bulk-id bulk-count bulk-index]}] args
          bulk-final? (and (some? bulk-count)
                           (some? bulk-index)
                           (= bulk-index (dec bulk-count)))
          bulk-op? (and (= event-type "success")
                        (some? bulk-count)
                        (or (= operation "save")
                            (= operation "delete")))
          show-banner? (or (= event-type "error")
                           (= event-type "info")
                           (not bulk-op?)
                           bulk-final?)
          banner-msg (cond
                       (= event-type "error")
                       (str "FS sync error: " error)

                       unchanged
                       (str "Script \"" script-name "\" unchanged")

                       (and bulk-op? bulk-final?)
                       (str bulk-count (if (= bulk-count 1) " file " " files ")
                            (if (= operation "delete") "deleted" "saved"))

                       :else
                       (str "Script \"" script-name "\" " operation "d"))
          ;; Compute post-tracking bulk names (simulates what track-bulk-name would do)
          pre-bulk-names (get-in state [:ui/system-bulk-names bulk-id])
          tracked-bulk-names (if (and bulk-id script-name)
                               ((fnil conj []) pre-bulk-names script-name)
                               pre-bulk-names)
          ;; State update: track bulk name + clear bulk names if final
          new-state (cond-> state
                      (some? bulk-id)
                      (assoc-in [:ui/system-bulk-names bulk-id] tracked-bulk-names)
                      (and bulk-id bulk-final?)
                      (update :ui/system-bulk-names dissoc bulk-id))
          ;; Deferred dispatches for downstream actions
          dxs (cond-> []
                ;; Mark scripts modified for error/unchanged saves
                (and (or (= event-type "error") unchanged)
                     (= operation "save")
                     script-name
                     (not bulk-id))
                (conj [:popup/ax.mark-scripts-modified [script-name]])
                ;; Show banner
                show-banner?
                (conj [:popup/ax.show-system-banner event-type banner-msg
                       {:bulk-op? bulk-op? :bulk-final? bulk-final?
                        :bulk-names tracked-bulk-names}]))]
      (cond-> {}
        (not= state new-state) (assoc :uf/db new-state)
        (seq dxs) (assoc :uf/dxs dxs)))

    :uf/unhandled-ax))