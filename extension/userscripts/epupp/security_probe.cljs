{:epupp/script-name "epupp/security_probe.cljs"
 :epupp/description "Dev/test: probes message registry access control"}

(ns epupp.security-probe
  (:require [clojure.string :as str]))

;; ============================================================
;; Message registry definition (mirrors content bridge registry)
;; ============================================================

(def ^:private response-messages
  "Messages that expect a response. Each entry maps type to
   {:sources [...] :response-type ...}"
  {"list-scripts"          {:sources ["epupp-page"]
                            :response-type "list-scripts-response"}
   "get-script"            {:sources ["epupp-page"]
                            :response-type "get-script-response"}
   "save-script"           {:sources ["epupp-page" "epupp-userscript"]
                            :response-type "save-script-response"}
   "rename-script"         {:sources ["epupp-page"]
                            :response-type "rename-script-response"}
   "delete-script"         {:sources ["epupp-page"]
                            :response-type "delete-script-response"}
   "load-manifest"         {:sources ["epupp-page" "epupp-userscript"]
                            :response-type "manifest-response"}
   "get-sponsored-username" {:sources ["epupp-page"]
                             :response-type "get-sponsored-username-response"}
   "install-userscript"    {:sources ["epupp-userscript"]
                            :response-type "install-response"}})

(def ^:private fire-and-forget-messages
  "Messages with no response expected."
  {"ws-connect"     {:sources ["epupp-page"]}
   "ws-send"        {:sources ["epupp-page"]}
   "sponsor-status" {:sources ["epupp-userscript"]}})

(def ^:private unregistered-messages
  "Types not in the registry - should always be dropped."
  ["evil-message" "request-save-token"])

;; ============================================================
;; Safe payloads for messages that need them
;; ============================================================

(defn- payload-for [msg-type]
  (case msg-type
    "save-script"     #js {:code "(ns probe-temp)" :name "epupp_security_probe_TEMP.cljs"}
    "get-script"      #js {:name "epupp/security_probe.cljs"}
    "rename-script"   #js {:oldName "nonexistent_xyz.cljs" :newName "nonexistent_abc.cljs"}
    "delete-script"   #js {:name "nonexistent_xyz.cljs"}
    "load-manifest"   #js {:code "{:epupp/script-name \"probe_temp.cljs\"}"}
    "install-userscript" #js {:url "https://example.com/nonexistent.cljs"}
    #js {}))

;; ============================================================
;; Probe single message
;; ============================================================

(defn- probe-message
  "Send a single probe message and return a promise resolving to
   {:status ...} where status is 'responded', 'dropped', or 'no-response'."
  [source msg-type response-type timeout-ms]
  (js/Promise.
   (fn [resolve _reject]
     (let [req-id (str "probe-" source "-" msg-type)
           timeout-id (atom nil)
           handler (fn handler [e]
                     (when (= (.-source e) js/window)
                       (let [msg (.-data e)]
                         (when (and msg
                                    (= "epupp-bridge" (.-source msg))
                                    (= response-type (.-type msg))
                                    (= req-id (.-requestId msg)))
                           (when-let [tid @timeout-id]
                             (js/clearTimeout tid))
                           (.removeEventListener js/window "message" handler)
                           (resolve {:status "responded"})))))]
       (if response-type
         (do
           (.addEventListener js/window "message" handler)
           (reset! timeout-id
                   (js/setTimeout
                    (fn []
                      (.removeEventListener js/window "message" handler)
                      (resolve {:status "dropped"}))
                    timeout-ms))
           (.postMessage js/window
                         (let [base (payload-for msg-type)]
                           (set! (.-source base) source)
                           (set! (.-type base) msg-type)
                           (set! (.-requestId base) req-id)
                           base)
                         "*"))
         ;; Fire-and-forget: no response type to listen for
         (do
           (.postMessage js/window
                         (let [base (payload-for msg-type)]
                           (set! (.-source base) source)
                           (set! (.-type base) msg-type)
                           (set! (.-requestId base) req-id)
                           base)
                         "*")
           (reset! timeout-id
                   (js/setTimeout
                    (fn []
                      (resolve {:status "no-response"}))
                    timeout-ms))))))))

;; ============================================================
;; Build probe plan
;; ============================================================

(defn- build-probe-plan
  "Return a sequence of {:source :type :response-type :timeout-ms} maps."
  []
  (let [plan (atom [])]
    ;; Response-bearing messages: probe each source
    (doseq [[msg-type {:keys [sources response-type]}] response-messages
            source sources]
      (swap! plan conj {:source source
                        :type msg-type
                        :response-type response-type
                        :timeout-ms 500}))
    ;; Fire-and-forget messages
    (doseq [[msg-type {:keys [sources]}] fire-and-forget-messages
            source sources]
      (swap! plan conj {:source source
                        :type msg-type
                        :response-type nil
                        :timeout-ms 200}))
    ;; Unregistered messages: probe from both sources
    (doseq [msg-type unregistered-messages
            source ["epupp-page" "epupp-userscript"]]
      (swap! plan conj {:source source
                        :type msg-type
                        :response-type (str msg-type "-response")
                        :timeout-ms 500}))
    @plan))

;; ============================================================
;; Sequential execution
;; ============================================================

(defn- run-probes-sequentially
  "Execute probe plan items one at a time, collecting results.
   Returns a promise resolving to {source -> {type -> result}}."
  [plan]
  (let [results (atom {})]
    (.then
     (reduce
      (fn [chain {:keys [source type response-type timeout-ms]}]
        (.then chain
               (fn [_]
                 (.then (probe-message source type response-type timeout-ms)
                        (fn [result]
                          (swap! results assoc-in [source type] result))))))
      (js/Promise.resolve nil)
      plan)
     (fn [_] @results))))

;; ============================================================
;; Output results
;; ============================================================

(defn- format-summary
  "Format results as a readable summary table."
  [results]
  (let [lines (atom [])]
    (doseq [[source type-map] (sort results)]
      (swap! lines conj (str "  " source ":"))
      (doseq [[msg-type {:keys [status]}] (sort type-map)]
        (swap! lines conj (str "    " msg-type " -> " status))))
    (str/join "\n" @lines)))

(defn- output-results! [results]
  (let [json-str (js/JSON.stringify (clj->js results) nil 2)]
    (js/console.log "[epupp-security-probe] Results:\n" (format-summary results))
    (.setAttribute js/document.body "data-security-probe" json-str)
    (.dispatchEvent js/document
                    (js/CustomEvent. "epupp-security-probe-complete"
                                    #js {:detail (clj->js results)}))))

;; ============================================================
;; Main
;; ============================================================

(defn- run-probe! []
  (js/console.log "[epupp-security-probe] Starting security surface probe...")
  (let [plan (build-probe-plan)]
    (-> (run-probes-sequentially plan)
        (.then output-results!)
        (.catch (fn [err]
                  (js/console.error "[epupp-security-probe] Probe failed:" err))))))

(run-probe!)
