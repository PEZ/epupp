 (ns epupp.fs
  "File system operations for managing userscripts from the REPL.")

(defn- send-and-receive
  "Helper: send message to bridge and return promise of response."
  [msg-type payload response-type]
  (js/Promise.
    (fn [resolve reject]
      (letfn [(handler [e]
                (when (= (.-source e) js/window)
                  (let [msg (.-data e)]
                    (when (and msg
                               (= "epupp-bridge" (.-source msg))
                               (= response-type (.-type msg)))
                      (.removeEventListener js/window "message" handler)
                      (resolve msg)))))]
        (.addEventListener js/window "message" handler)
        (.postMessage js/window
          (clj->js (assoc payload :source "epupp-page" :type msg-type))
          "*")))))

(defn cat
  "Get script code by name(s). Returns promise.
   Single name: returns code string or nil
   Vector of names: returns map of name->code (nil for missing)

   Examples: (epupp.fs/cat \"my-script.cljs\")
             (epupp.fs/cat [\"script1.cljs\" \"script2.cljs\"])"
  [name-or-names]
  (if (vector? name-or-names)
    ;; Bulk mode: fetch all and return map
    (let [names name-or-names]
      (-> (js/Promise.all
            (to-array
              (map (fn [n]
                     (-> (send-and-receive "get-script" {:name n} "get-script-response")
                         (.then (fn [msg]
                                  [n (when (.-success msg) (.-code msg))]))))
                   names)))
          (.then (fn [results]
                   (into {} results)))))
    ;; Single mode: return code or nil
    (-> (send-and-receive "get-script" {:name name-or-names} "get-script-response")
        (.then (fn [msg]
                 (when (.-success msg) (.-code msg)))))))

(defn ls
  "List all scripts. Returns promise of vector with script info.
   Each script has :fs/name, :fs/enabled, :fs/match, :fs/modified keys.

   Example: (epupp.fs/ls)"
  []
  (-> (send-and-receive "list-scripts" {} "list-scripts-response")
      (.then (fn [msg]
               (if (.-success msg)
                 (js->clj (.-scripts msg) :keywordize-keys true)
                 [])))))

(defn save!
  "Save code(s) to Epupp. Parses manifest from code.
   Returns promise of result map with :fs/success, :fs/name, :fs/pending-confirmation.

   Opts - :fs/enabled bool - defaults to true
        - :fs/force?  bool - when true, skip confirmation and clear any pending (default: false)

   Single mode returns {:fs/success bool :fs/name ... :fs/pending-confirmation bool}
   Bulk mode returns map of index->{:fs/success ... :fs/name ... :fs/pending-confirmation ...}

   (epupp.fs/save! \"(ns my-script) ...\")
   (epupp.fs/save! \"(ns my-script) ...\" {:fs/enabled false})
   (epupp.fs/save! \"(ns my-script) ...\" {:fs/force? true})
   (epupp.fs/save! [code1 code2])"
  ([code-or-codes] (save! code-or-codes {}))
  ([code-or-codes opts]
   (let [force? (get opts :fs/force?)
         enabled (get opts :fs/enabled true)]
     (if (vector? code-or-codes)
       ;; Bulk mode: queue all for confirmation, return map of index->result
       (let [codes code-or-codes]
         (-> (js/Promise.all
               (to-array
                 (map-indexed (fn [idx code]
                                (if force?
                                  (-> (send-and-receive "save-script" {:code code :enabled enabled :force true} "save-script-response")
                                      (.then (fn [msg]
                                               [idx {:fs/success (.-success msg)
                                                     :fs/name (.-name msg)
                                                     :fs/error (.-error msg)}])))
                                  (-> (send-and-receive "queue-save-script" {:code code :enabled enabled} "queue-save-script-response")
                                      (.then (fn [msg]
                                               [idx {:fs/success (.-success msg)
                                                     :fs/pending-confirmation (.-pending-confirmation msg)
                                                     :fs/name (.-name msg)
                                                     :fs/error (.-error msg)}])))))
                              codes)))
             (.then (fn [results]
                      (into {} results)))))
       ;; Single mode
       (if force?
         (-> (send-and-receive "save-script" {:code code-or-codes :enabled enabled :force true} "save-script-response")
             (.then (fn [msg]
                      {:fs/success (.-success msg)
                       :fs/name (.-name msg)
                       :fs/error (.-error msg)})))
         (-> (send-and-receive "queue-save-script" {:code code-or-codes :enabled enabled} "queue-save-script-response")
             (.then (fn [msg]
                      {:fs/success (.-success msg)
                       :fs/pending-confirmation (.-pending-confirmation msg)
                       :fs/name (.-name msg)
                       :fs/error (.-error msg)}))))))))

(defn mv!
  "Rename a script. Returns promise of result map.
   Opts - :fs/force? bool - when true, skip confirmation and clear pending (default: false)

   Returns {:fs/success bool :fs/pending-confirmation bool :fs/from-name ... :fs/to-name ...}
   When queued, :fs/success is true (command accepted) and :fs/pending-confirmation is true.

   (epupp.fs/mv! \"old-name.cljs\" \"new-name.cljs\")         ; queues for confirmation
   (epupp.fs/mv! \"old.cljs\" \"new.cljs\" {:fs/force? true}) ; renames immediately"
  ([from-name to-name] (mv! from-name to-name {}))
  ([from-name to-name opts]
   (let [force? (get opts :fs/force?)]
     (if force?
       ;; Immediate rename (force clears any pending)
       (-> (send-and-receive "rename-script" {:from from-name :to to-name :force true} "rename-script-response")
           (.then (fn [msg]
                    {:fs/success (.-success msg)
                     :fs/from-name from-name
                     :fs/to-name to-name
                     :fs/error (.-error msg)})))
       ;; Queue for confirmation
       (-> (send-and-receive "queue-rename-script" {:from from-name :to to-name} "queue-rename-script-response")
           (.then (fn [msg]
                    {:fs/success (.-success msg)
                     :fs/pending-confirmation (.-pending-confirmation msg)
                     :fs/from-name from-name
                     :fs/to-name to-name
                     :fs/error (.-error msg)})))))))

(defn rm!
  "Delete script(s) by name. Returns promise.
   Opts - :fs/force? bool - when true, skip confirmation and clear pending (default: false)

   Returns {:fs/success bool :fs/pending-confirmation bool :fs/name ...}
   When queued, :fs/success is true (command accepted) and :fs/pending-confirmation is true.

   Bulk mode queues all for confirmation, returns map of name->{:fs/success ... :fs/pending-confirmation ...}

   (epupp.fs/rm! \"my-script.cljs\")                   ; queues for confirmation
   (epupp.fs/rm! \"my-script.cljs\" {:fs/force? true}) ; deletes immediately
   (epupp.fs/rm! [\"script1.cljs\" \"script2.cljs\"])    ; queues all for confirmation"
  ([name-or-names] (rm! name-or-names {}))
  ([name-or-names opts]
   (let [force? (get opts :fs/force?)]
     (if (vector? name-or-names)
       ;; Bulk mode: queue all for confirmation (or force all)
       (let [names name-or-names]
         (-> (js/Promise.all
               (to-array
                 (map (fn [n]
                        (if force?
                          (-> (send-and-receive "delete-script" {:name n :force true} "delete-script-response")
                              (.then (fn [msg]
                                       [n {:fs/success (.-success msg)
                                           :fs/error (.-error msg)}])))
                          (-> (send-and-receive "queue-delete-script" {:name n} "queue-delete-script-response")
                              (.then (fn [msg]
                                       [n {:fs/success (.-success msg)
                                           :fs/pending-confirmation (.-pending-confirmation msg)
                                           :fs/error (.-error msg)}])))))
                      names)))
             (.then (fn [results]
                      (into {} results)))))
       ;; Single mode
       (if force?
         ;; Immediate delete (force clears any pending)
         (-> (send-and-receive "delete-script" {:name name-or-names :force true} "delete-script-response")
             (.then (fn [msg]
                      {:fs/success (.-success msg)
                       :fs/name name-or-names
                       :fs/error (.-error msg)})))
         ;; Queue for confirmation
         (-> (send-and-receive "queue-delete-script" {:name name-or-names} "queue-delete-script-response")
             (.then (fn [msg]
                      {:fs/success (.-success msg)
                       :fs/pending-confirmation (.-pending-confirmation msg)
                       :fs/name name-or-names
                       :fs/error (.-error msg)}))))))))
