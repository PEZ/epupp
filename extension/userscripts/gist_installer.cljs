;; Scittle Tamper Gist Installer - Runs in Scittle on GitHub gist pages
;;
;; This userscript scans gist code blocks for install manifests,
;; adds Install buttons, and sends parsed data to the extension.

(ns gist-installer
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(def ^:private manifest-marker
  ";; Scittle Tamper UserScript")

(defn has-manifest? [code-text]
  (and code-text (str/includes? code-text manifest-marker)))

(defn extract-manifest
  "Extract and parse the #_{...} EDN manifest from code text"
  [code-text]
  (when-let [marker-idx (str/index-of code-text manifest-marker)]
    (let [after-marker (subs code-text (+ marker-idx (count manifest-marker)))
          discard-idx (str/index-of after-marker "#_")]
      (when discard-idx
        (try
          (let [after-discard (subs after-marker (+ discard-idx 2))
                manifest (edn/read-string after-discard)]
            (when (map? manifest)
              manifest))
          (catch js/Error e
            (js/console.error "[Gist Installer] Parse error:" e)
            nil))))))

(defn create-install-button []
  (let [btn (js/document.createElement "button")]
    (set! (.-textContent btn) "Install to Scittle Tamper")
    (set! (.-className btn) "scittle-tamper-install-btn")
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
               "<button id='scittle-tamper-cancel' style='padding: 6px 16px; background: #f6f8fa; border: 1px solid #d0d7de; border-radius: 6px; cursor: pointer;'>Cancel</button>"
               "<button id='scittle-tamper-confirm' style='padding: 6px 16px; background: #2ea44f; color: white; border: 1px solid rgba(27,31,36,0.15); border-radius: 6px; cursor: pointer;'>Install</button>"
               "</div>"))

    (.appendChild overlay modal)
    (.appendChild js/document.body overlay)

    ;; Event handlers
    (let [confirm-btn (.querySelector modal "#scittle-tamper-confirm")
          cancel-btn (.querySelector modal "#scittle-tamper-cancel")
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
                     (when (and (= (.-source data) "scittle-tamper-bridge")
                               (= (.-type data) "install-response"))
                       (.removeEventListener js/window "message" listener)
                       (callback #js {:success (.-success data)
                                     :error (.-error data)}))))]
    (.addEventListener js/window "message" listener)
    ;; Send install request to content bridge
    ;; Must use clj->js on manifest so postMessage serializes the actual keys
    (.postMessage js/window
                  #js {:source "scittle-tamper-userscript"
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
