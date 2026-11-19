(ns builtin-scripts
  "The built-in script catalog and the pure functions that keep stored
   built-ins in line with the bundled ones. storage runs the sync."
  (:require [manifest-parser :as mp]
            [script-utils :as script-utils]))

;; ============================================================
;; Built-in catalog
;; ============================================================

(def ^:private base-builtins
  [{:script/id "epupp-builtin-internal-helpers"
    :path "userscripts/epupp/internal/helpers.cljs"
    :name "epupp/internal/helpers.cljs"
    :always-enabled? true}
   {:script/id "epupp-builtin-ui"
    :path "userscripts/epupp/ui.cljs"
    :name "epupp/ui.cljs"
    :always-enabled? true}
   {:script/id "epupp-builtin-storage"
    :path "userscripts/epupp/storage.cljs"
    :name "epupp/storage.cljs"
    :always-enabled? true}
   {:script/id "epupp-builtin-tools"
    :path "userscripts/epupp/tools.cljs"
    :name "epupp/tools.cljs"
    :always-enabled? true}
   {:script/id "epupp-builtin-web-userscript-installer"
    :path "userscripts/epupp/web_userscript_installer.cljs"
    :name "epupp/web_userscript_installer.cljs"
    :special? true
    :web-installer-scan true}
   {:script/id "epupp-builtin-sponsor-check"
    :path "userscripts/epupp/sponsor.cljs"
    :name "epupp/sponsor.cljs"
    :always-enabled? true}])

(def ^:private security-probe-builtin
  {:script/id "epupp-builtin-security-probe"
   :path "userscripts/epupp/security_probe.cljs"
   :name "epupp/security_probe.cljs"})

(def bundled-builtins
  (if (.-dev js/EXTENSION_CONFIG)
    (conj base-builtins security-probe-builtin)
    base-builtins))

(defn bundled-builtin-ids
  "Return the list of bundled built-in script IDs."
  []
  (mapv :script/id bundled-builtins))

;; ============================================================
;; Stored built-ins
;; ============================================================

(defn stale-builtin-ids
  "Return built-in script IDs that are not bundled with this extension version."
  [scripts bundled-ids]
  (->> scripts
       (filterv (fn [script]
                  (and (script-utils/builtin-script? script)
                       (not (contains? bundled-ids (:script/id script))))))
       (mapv :script/id)))

(defn remove-stale-builtins
  "Remove built-in scripts not bundled with this extension version."
  [scripts bundled-ids]
  (filterv (fn [script]
             (not (and (script-utils/builtin-script? script)
                       (not (contains? bundled-ids (:script/id script))))))
           scripts))

(defn merge-imported-scripts
  "Return the imported scripts followed by the built-ins from scripts.
   Imported entries with built-in ids are skipped, so an export file
   cannot replace bundled code."
  [scripts imported]
  (into (filterv #(not (some-> (:script/id %) (.startsWith "epupp-builtin-")))
                 imported)
        (filter script-utils/builtin-script? scripts)))

;; ============================================================
;; Bundled scripts
;; ============================================================

(defn- normalize-auto-run-match
  "Normalize auto-run match value to vector or nil when absent."
  [match-raw]
  (cond
    (nil? match-raw) nil
    (string? match-raw) (if (empty? match-raw) [] [match-raw])
    (js/Array.isArray match-raw) (vec match-raw)
    :else nil))

(defn- manifest-has-auto-run-key?
  "Check if manifest explicitly includes :epupp/auto-run-match."
  [manifest]
  (some #(= % "epupp/auto-run-match")
        (get manifest "found-keys")))

(defn- normalize-inject [inject-raw]
  (cond
    (nil? inject-raw) []
    (string? inject-raw) [inject-raw]
    (js/Array.isArray inject-raw) (vec inject-raw)
    :else []))

(defn- extract-bundled-manifest-data [manifest]
  (when manifest
    (let [has-auto-run-key? (manifest-has-auto-run-key? manifest)
          raw-match (normalize-auto-run-match (get manifest "auto-run-match"))]
      {:manifest/name (or (get manifest "script-name")
                          (get manifest "raw-script-name"))
       :manifest/run-at (script-utils/normalize-run-at (get manifest "run-at"))
       :manifest/description (get manifest "description")
       :manifest/inject (normalize-inject (get manifest "inject"))
       :manifest/match (if has-auto-run-key? (or raw-match []) [])})))

(defn build-bundled-script
  "Build a built-in script map from bundled code and manifest metadata."
  [bundled code]
  (let [manifest (try (mp/extract-manifest code)
                      (catch :default _ nil))
        mdata (extract-bundled-manifest-data manifest)
        raw-name (or (:manifest/name mdata) (:name bundled))
        run-at (:manifest/run-at mdata)
        description (:manifest/description mdata)
        inject (or (:manifest/inject mdata) [])
        match (:manifest/match mdata)]
    (cond-> {:script/id (:script/id bundled)
             :script/code code
             :script/builtin? true
             :script/source :source/built-in}
      raw-name (assoc :script/name raw-name)
      (some? match) (assoc :script/match match)
      (some? run-at) (assoc :script/run-at run-at)
      description (assoc :script/description description)
      (seq inject) (assoc :script/inject inject)
      (:always-enabled? bundled) (assoc :script/always-enabled? true)
      (:special? bundled) (assoc :script/special? true)
      (:web-installer-scan bundled) (assoc :script/web-installer-scan true))))

(defn builtin-update-needed?
  "Check whether a built-in script needs to be updated from bundled code."
  [existing desired]
  (or (nil? existing)
      (not= (:script/code existing) (:script/code desired))
      (not= (:script/name existing) (:script/name desired))
      (not= (:script/match existing) (:script/match desired))
      (not= (:script/description existing) (:script/description desired))
      (not= (:script/run-at existing) (:script/run-at desired))
      (not= (:script/inject existing) (:script/inject desired))
      (not= (:script/special? existing) (:script/special? desired))
      (not= (:script/web-installer-scan existing) (:script/web-installer-scan desired))))
