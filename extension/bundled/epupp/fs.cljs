(ns epupp.fs
  "File system operations for managing userscripts from the REPL.

   Write operations (save!, mv!, rm!) require FS REPL Sync to be enabled in settings.
   Read operations (ls, show) always work.

   The :fs/force? option enables overwrite behavior (like Unix -f flag):
   - save! with :fs/force? overwrites existing script with same name
   - mv! with :fs/force? overwrites target if it exists")

(defonce ^:private !request-id (atom 0))

(defn- next-request-id []
  (swap! !request-id inc))

(defn- send-and-receive
  "Helper: send message to bridge and return promise of response."
  [msg-type payload response-type]
  (let [req-id (next-request-id)]
    (js/Promise.
      (fn [resolve _reject]
        (letfn [(handler [e]
                  (when (= (.-source e) js/window)
                    (let [msg (.-data e)]
                      (when (and msg
                                 (= "epupp-bridge" (.-source msg))
                                 (= response-type (.-type msg))
                                 (= req-id (.-requestId msg)))
                        (.removeEventListener js/window "message" handler)
                        (resolve msg)))))]
          (.addEventListener js/window "message" handler)
          (.postMessage js/window
            (clj->js (assoc payload :source "epupp-page" :type msg-type :requestId req-id))
            "*"))))))

(defn- ensure-success!
  "Throw when response indicates failure, otherwise pass through msg."
  [msg]
  (if (.-success msg)
    msg
    (throw (js/Error. (or (.-error msg) "Unknown error")))))

(defn show
  "Get script code by name(s). Returns promise.
   Single name: returns code string or nil
   Vector of names: returns map of name->code (nil for missing)

   Examples: (epupp.fs/show \"my-script.cljs\")
             (epupp.fs/show [\"script1.cljs\" \"script2.cljs\"])"
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
  "Save code to Epupp. Parses manifest from code.
   Requires FS REPL Sync to be enabled in settings.

   Returns promise of {:fs/success bool :fs/name string :fs/error string?}

   Opts:
   - :fs/enabled bool - script enabled state (default: true)
   - :fs/force?  bool - overwrite existing script with same name (default: false)

   Examples:
   (epupp.fs/save! code)
   (epupp.fs/save! code {:fs/enabled false})
   (epupp.fs/save! code {:fs/force? true})  ; overwrite existing
   (epupp.fs/save! [code1 code2])           ; bulk save"
  ([code-or-codes] (save! code-or-codes {}))
  ([code-or-codes opts]
   (let [force? (get opts :fs/force?)
         enabled (get opts :fs/enabled true)]
     (if (vector? code-or-codes)
       ;; Bulk mode - use map-indexed (realized by to-array for Promise.all)
       (-> (js/Promise.all
             (to-array
               (map-indexed (fn [idx code]
                              (-> (send-and-receive "save-script" {:code code :enabled enabled :force force?} "save-script-response")
                                  (.then ensure-success!)
                                  (.then (fn [msg]
                                           [idx {:fs/success (.-success msg)
                                                 :fs/name (.-name msg)
                                                 :fs/error (.-error msg)}]))))
                            code-or-codes)))
           (.then (fn [results]
                    (into {} results))))
       ;; Single mode
       (-> (send-and-receive "save-script" {:code code-or-codes :enabled enabled :force force?} "save-script-response")
           (.then ensure-success!)
           (.then (fn [msg]
                    {:fs/success (.-success msg)
                     :fs/name (.-name msg)
                     :fs/error (.-error msg)})))))))

(defn mv!
  "Rename a script. Requires FS REPL Sync to be enabled in settings.

   Returns promise of {:fs/success bool :fs/from-name ... :fs/to-name ... :fs/error?}

   Opts:
   - :fs/force? bool - overwrite target if it exists (default: false)

   Examples:
   (epupp.fs/mv! \"old.cljs\" \"new.cljs\")
   (epupp.fs/mv! \"old.cljs\" \"existing.cljs\" {:fs/force? true})  ; overwrites"
  ([from-name to-name] (mv! from-name to-name {}))
  ([from-name to-name opts]
   (let [force? (get opts :fs/force?)]
     (-> (send-and-receive "rename-script" {:from from-name :to to-name :force force?} "rename-script-response")
         (.then ensure-success!)
         (.then (fn [msg]
                  {:fs/success (.-success msg)
                   :fs/from-name from-name
                   :fs/to-name to-name
                   :fs/error (.-error msg)}))))))

(defn rm!
  "Delete script(s) by name. Requires FS REPL Sync to be enabled in settings.

   Returns promise of {:fs/success bool :fs/name string :fs/existed? bool}
   - :fs/existed? is false if the script didn't exist (still succeeds)
   - Fails only for protected scripts (built-in)

   Bulk mode returns map of name->{:fs/success ... :fs/existed? ...}

   Examples:
   (epupp.fs/rm! \"my-script.cljs\")
   (epupp.fs/rm! [\"script1.cljs\" \"script2.cljs\"])"
  [name-or-names]
  (let [not-found-error? (fn [msg]
                           (let [err (.-error msg)]
                             (and err (or (.includes err "not found")
                                          (.includes err "does not exist")))))]
    (if (vector? name-or-names)
      ;; Bulk mode - use mapv for eager evaluation before Promise.all
      (-> (js/Promise.all
           (to-array
            (mapv (fn [n]
                    (-> (send-and-receive "delete-script" {:name n} "delete-script-response")
                        (.then (fn [msg]
                                 (cond
                                   (.-success msg)
                                   [n {:fs/success true :fs/name n :fs/existed? true}]

                                   (not-found-error? msg)
                                   [n {:fs/success true :fs/name n :fs/existed? false}]

                                   :else
                                   (throw (js/Error. (or (.-error msg) "Unknown error"))))))))
                  name-or-names)))
          (.then (fn [results]
                   (into {} results))))
      ;; Single mode
      (-> (send-and-receive "delete-script" {:name name-or-names} "delete-script-response")
          (.then (fn [msg]
                   (cond
                     (.-success msg)
                     {:fs/success true :fs/name name-or-names :fs/existed? true}

                     (not-found-error? msg)
                     {:fs/success true :fs/name name-or-names :fs/existed? false}

                     :else
                     (throw (js/Error. (or (.-error msg) "Unknown error"))))))))))


