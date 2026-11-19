(ns popup.effects.script-effects
  (:require [popup.utils :as popup-utils]
            [script-utils :as script-utils]
            [manifest-parser :as mp]))

(defn load-scripts! [dispatch]
  (js/chrome.storage.local.get
   #js ["scripts"]
   (fn [result]
     (let [scripts (script-utils/parse-scripts (.-scripts result) {:extract-manifest mp/extract-manifest})]
       (dispatch [[:db/ax.assoc :scripts/list scripts]])))))

(defn toggle-script! [dispatch scripts script-id _matching-pattern]
  (let [updated (popup-utils/toggle-script-in-list scripts script-id)]
    (js/chrome.runtime.sendMessage #js {:type "popup-toggle-script" :scriptId script-id})
    (dispatch [[:db/ax.assoc :scripts/list updated]])))

(defn delete-script! [dispatch scripts script-id]
  (let [updated (popup-utils/remove-script-from-list scripts script-id)]
    (js/chrome.runtime.sendMessage #js {:type "popup-delete-script" :scriptId script-id})
    (dispatch [[:db/ax.assoc :scripts/list updated]])))

(defn inspect-script! [_dispatch script]
  (js/chrome.storage.local.set
   #js {:editingScript #js {:id (:script/id script)
                            :name (:script/name script)
                            :match (first (:script/match script))
                            :code (:script/code script)
                            :description (:script/description script)}}))

(defn ^:async evaluate-script! [_dispatch script]
  (let [tab (js-await (popup-utils/get-active-tab))]
    (js/chrome.runtime.sendMessage
     #js {:type "evaluate-script"
          :tabId (.-id tab)
          :scriptId (:script/id script)
          :code (:script/code script)
          :inject (clj->js (:script/inject script))})))

(defn export-scripts! [_dispatch]
  (js/chrome.storage.local.get
   #js ["scripts"]
   (fn [result]
     (let [all-scripts (or (.-scripts result) #js [])
           user-scripts (.filter all-scripts
                                 (fn [s]
                                   (let [id (.-id s)]
                                     (not (and id (.startsWith id "epupp-builtin-"))))))
           json-str (js/JSON.stringify user-scripts nil 2)
           blob (js/Blob. #js [json-str] #js {:type "application/json"})
           url (js/URL.createObjectURL blob)
           link (js/document.createElement "a")
           filename (str "epupp-scripts-" (.toISOString (js/Date.)) ".json")]
       (set! (.-href link) url)
       (set! (.-download link) filename)
       (js/document.body.appendChild link)
       (.click link)
       (js/document.body.removeChild link)
       (js/URL.revokeObjectURL url)))))

(defn trigger-import! [dispatch]
  (let [input (js/document.createElement "input")]
    (set! (.-type input) "file")
    (set! (.-accept input) ".json")
    (set! (.-onchange input)
          (fn [e]
            (when-let [file (aget (.. e -target -files) 0)]
              (let [reader (js/FileReader.)]
                (set! (.-onload reader)
                      (fn [e]
                        (try
                          (let [json-str (.. e -target -result)
                                scripts (js/JSON.parse json-str)]
                            (dispatch [[:script/ax.handle-import scripts]]))
                          (catch :default err
                            (js/alert (str "Failed to parse JSON: " (.-message err)))))))
                (.readAsText reader file)))))
    (.click input)))

(defn import-scripts! [dispatch imported-scripts]
  (js/chrome.runtime.sendMessage
   #js {:type "popup-import-scripts" :scripts imported-scripts}
   (fn [response]
     (if (some-> response .-success)
       (do (js/alert "Scripts imported successfully! Reloading...")
           (dispatch [[:script/ax.load-scripts]]))
       (js/alert (str "Failed to import scripts: " (some-> response .-error)))))))
