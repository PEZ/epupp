;; Epupp Gist Installer - Runs in Scittle on GitHub gist pages
;;
;; This userscript scans gist code blocks for install manifests,
;; adds Install buttons, and sends parsed data to the extension.
;;
;; MANIFEST FORMAT:
;; For a gist to be installable, the first form must have Epupp metadata.
;; Typically this is the ns form:
;;
;;   ^{:epupp/script-name "my_cool_script.cljs"
;;     :epupp/site-match "https://example.com/*"
;;     :epupp/description "Optional description of what the script does"}
;;   (ns my-cool-script)
;;
;;   (println "Script code starts here...")
;;
;; Manifest metadata keys (all namespaced under epupp/):
;;   :epupp/script-name  - Required. Display name for the script
;;   :epupp/site-match   - Required. URL pattern (glob format) where script runs
;;   :epupp/description  - Optional. Brief description shown in popup
;;
;; The metadata is extracted and converted to the internal script format:
;;   {:script/name "..."
;;    :script/match ["..."]     ; site-match becomes a vector
;;    :script/description "..." ; optional
;;    :script/code "..."}       ; full gist content
;;
;; Note: :script/id, :script/enabled, :script/created, :script/modified,
;; and :script/approved-patterns are generated/managed by the extension.

(ns gist-installer
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(defn- get-first-form-meta
  "Read the first form from code text and return its metadata"
  [code-text]
  (try
    (let [form (edn/read-string code-text)]
      (meta form))
    (catch js/Error e
      (js/console.error "[Gist Installer] Parse error:" e)
      nil)))

(defn has-manifest?
  "Check if the first form has :epupp/script-name metadata"
  [code-text]
  (when code-text
    (let [m (get-first-form-meta code-text)]
      (get m :epupp/script-name))))

(defn extract-manifest
  "Extract manifest from the first form's metadata.
   Returns map with :script-name, :site-match, :description (if present)"
  [code-text]
  (when-let [m (get-first-form-meta code-text)]
    (when (get m :epupp/script-name)
      {:script-name (get m :epupp/script-name)
       :site-match (get m :epupp/site-match)
       :description (get m :epupp/description)})))

(defn create-install-button []
  (let [btn (js/document.createElement "button")]
    (set! (.-textContent btn) "Install to Epupp")
    (set! (.-className btn) "epupp-install-btn")
    (set! (.. btn -style -cssText)
          "margin: 8px 0; padding: 6px 12px; background: #2ea44f; color: white; border: 1px solid rgba(27,31,36,0.15); border-radius: 6px; font-size: 14px; font-weight: 500; cursor: pointer;")
    btn))

(defn show-confirmation-modal
  [manifest gist-url on-confirm on-cancel]
  (let [overlay (js/document.createElement "div")
        modal (js/document.createElement "div")
        script-name (get manifest :script-name)
        site-match (get manifest :site-match)
        description (get manifest :description)]
    ;; Overlay styles
    (set! (.. overlay -style -cssText)
          "position: fixed; top: 0; left: 0; right: 0; bottom: 0; background: rgba(0,0,0,0.5); z-index: 9998; display: flex; align-items: center; justify-content: center;")

    ;; Modal styles
    (set! (.. modal -style -cssText)
          "background: white; padding: 24px; border-radius: 8px; max-width: 500px; box-shadow: 0 8px 24px rgba(0,0,0,0.3); z-index: 9999;")

    ;; Modal content
    (set! (.-innerHTML modal)
          (str "<h2 style='margin-top: 0;'>Install Userscript</h2>"
               "<p><strong>Name:</strong> " (or script-name "Unknown") "</p>"
               (when description
                 (str "<p><strong>Description:</strong> " description "</p>"))
               "<p><strong>Site Pattern:</strong> " (or site-match "None") "</p>"
               "<p><strong>Source:</strong><br><code>" gist-url "</code></p>"
               "<p style='color: #666; font-size: 14px;'>This will download and install the script from the gist file above.</p>"
               "<div style='display: flex; gap: 8px; justify-content: flex-end; margin-top: 16px;'>"
               "<button id='epupp-cancel' style='padding: 6px 16px; background: #f6f8fa; border: 1px solid #d0d7de; border-radius: 6px; cursor: pointer;'>Cancel</button>"
               "<button id='epupp-confirm' style='padding: 6px 16px; background: #2ea44f; color: white; border: 1px solid rgba(27,31,36,0.15); border-radius: 6px; cursor: pointer;'>Install</button>"
               "</div>"))

    (.appendChild overlay modal)
    (.appendChild js/document.body overlay)

    ;; Event handlers
    (let [confirm-btn (.querySelector modal "#epupp-confirm")
          cancel-btn (.querySelector modal "#epupp-cancel")
          close-modal (fn []
                        (.removeChild js/document.body overlay))]
      (.addEventListener confirm-btn "click"
                         (fn []
                           (close-modal)
                           (on-confirm)))
      (.addEventListener cancel-btn "click"
                         (fn []
                           (close-modal)
                           (on-cancel)))
      (.addEventListener overlay "click"
                         (fn [e]
                           (when (= (.-target e) overlay)
                             (close-modal)
                             (on-cancel)))))))

(defn get-gist-file-text
  "Get text content from a gist file"
  [file-container]
  (let [lines (.querySelectorAll file-container ".js-file-line")
        line-array (js/Array.from lines)]
    (str/join "\n" (map #(.-textContent %) line-array))))

(defn- send-install-request
  "Send install request to extension via postMessage (content bridge forwards it)"
  [manifest script-url callback]
  ;; Listen for response from content bridge
  (let [listener (fn listener[event]
                   (let [data (.-data event)]
                     (when (and (= (.-source data) "epupp-bridge")
                               (= (.-type data) "install-response"))
                       (.removeEventListener js/window "message" listener)
                       (callback #js {:success (.-success data)
                                     :error (.-error data)}))))]
    (.addEventListener js/window "message" listener)
    ;; Send install request to content bridge
    ;; Must use clj->js on manifest so postMessage serializes the actual keys
    (.postMessage js/window
                  #js {:source "epupp-userscript"
                       :type "install-userscript"
                       :manifest (clj->js manifest)
                       :scriptUrl script-url}
                  "*")))

(defn get-gist-raw-url
  "Extract the raw gist URL from the Raw button link"
  [file-container]
  (when-let [file-actions (.querySelector file-container ".file-actions")]
    (when-let [raw-link (.querySelector file-actions "a[href*='/raw/']")]
      (.-href raw-link))))

(defn attach-install-button
  [file-container code-text]
  (let [btn (create-install-button)
        header (.querySelector file-container ".gist-blob-name")
        gist-url (get-gist-raw-url file-container)]
    (.addEventListener btn "click"
                       (fn [e]
                         (.preventDefault e)
                         (if-let [manifest (extract-manifest code-text)]
                           (if gist-url
                             (show-confirmation-modal
                              manifest
                              gist-url
                              ;; On confirm
                              (fn []
                                (send-install-request
                                 manifest
                                 gist-url
                               (fn [response]
                                 (if (and response (.-success response))
                                   (do
                                     (set! (.-textContent btn) "✓ Installed")
                                     (set! (.-disabled btn) true)
                                     (set! (.. btn -style -background) "#6c757d"))
                                   (do
                                     (js/console.error "[Gist Installer] Install failed:" (.-error response))
                                     (set! (.-textContent btn) "Install Failed")
                                     (set! (.. btn -style -background) "#dc3545"))))))
                              ;; On cancel
                              (fn []
                                (js/console.log "[Gist Installer] User cancelled")))
                             ;; No gist URL found
                             (do
                               (set! (.-textContent btn) "URL Error")
                               (set! (.. btn -style -background) "#dc3545")))
                           ;; Parse failed
                           (do
                             (set! (.-textContent btn) "Parse Error")
                             (set! (.. btn -style -background) "#dc3545")))))
    (when header
      (let [parent (.-parentElement header)]
        (.insertBefore parent btn (.-nextSibling header))))))

(defn scan-gist-files []
  (let [file-containers (.querySelectorAll js/document ".file")
        container-array (js/Array.from file-containers)]
    (doseq [container container-array]
      (let [code-text (get-gist-file-text container)]
        (when (has-manifest? code-text)
          (js/console.log "[Gist Installer] Found installable script")
          (attach-install-button container code-text))))))

;; Main entry point
(defn init! []
  (js/console.log "[Gist Installer] Initializing...")
  (if (= js/document.readyState "loading")
    (.addEventListener js/document "DOMContentLoaded" scan-gist-files)
    (scan-gist-files))
  (js/console.log "[Gist Installer] Ready"))

(init!)
