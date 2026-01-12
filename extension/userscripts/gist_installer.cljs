;; Epupp Gist Installer - Runs in Scittle on GitHub gist pages
;;
;; This userscript scans gist code blocks for install manifests,
;; adds Install buttons, and sends parsed data to the extension.
;;
;; MANIFEST FORMAT:
;; For a gist to be installable, the first form must be a data map
;; containing Epupp manifest keys:
;;
;;   {:epupp/script-name "My Cool Script"
;;    :epupp/site-match "https://example.com/*"
;;    :epupp/description "Optional description of what the script does"
;;    :epupp/run-at "document-idle"}
;;
;;   (ns my-cool-script)
;;
;;   (println "Script code starts here...")
;;
;; Manifest keys (all namespaced under epupp/):
;;   :epupp/script-name  - Required. Display name (normalized to filename format)
;;   :epupp/site-match   - Required. URL pattern (glob format) where script runs
;;   :epupp/description  - Optional. Brief description shown in popup
;;   :epupp/run-at       - Optional. "document-start", "document-end", or "document-idle" (default)
;;
;; Names are normalized for consistency (e.g., "My Cool Script" -> "my_cool_script.cljs").
;; The extension generates :script/id, :script/enabled, timestamps, and :script/approved-patterns.

(ns gist-installer
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

;; ============================================================
;; Script name normalization (mirrors script-utils logic)
;; ============================================================

(def valid-run-at-values
  #{"document-start" "document-end" "document-idle"})

(def default-run-at "document-idle")

(defn normalize-script-name
  "Normalize a script name to a consistent format.
   - Lowercase
   - Replace spaces, dashes, and dots with underscores
   - Remove invalid characters
   - Append .cljs extension"
  [input-name]
  (let [base-name (if (str/ends-with? input-name ".cljs")
                    (subs input-name 0 (- (count input-name) 5))
                    input-name)]
    (-> base-name
        (str/lower-case)
        (str/replace #"[\s.-]+" "_")
        (str/replace #"[^a-z0-9_/]" "")
        (str ".cljs"))))

;; ============================================================
;; Manifest parsing (data map format)
;; ============================================================

(defn- get-first-form
  "Read the first form from code text.
   Returns the form if it's a map, nil otherwise."
  [code-text]
  (try
    (let [form (edn/read-string code-text)]
      (when (map? form) form))
    (catch js/Error e
      (js/console.error "[Gist Installer] Parse error:" e)
      nil)))

(defn has-manifest?
  "Check if the first form is a map with :epupp/script-name"
  [code-text]
  (when code-text
    (when-let [m (get-first-form code-text)]
      (get m :epupp/script-name))))

(defn extract-manifest
  "Extract manifest from the first form (must be a data map).
   Returns map with normalized name, site-match, description, and run-at."
  [code-text]
  (when-let [m (get-first-form code-text)]
    (when-let [raw-name (get m :epupp/script-name)]
      (let [normalized-name (normalize-script-name raw-name)
            raw-run-at (get m :epupp/run-at)
            run-at (if (contains? valid-run-at-values raw-run-at)
                     raw-run-at
                     default-run-at)
            ;; site-match can be string or vector - normalize to string
            raw-site-match (get m :epupp/site-match)
            site-match (if (vector? raw-site-match)
                         (first raw-site-match)
                         raw-site-match)]
        {:script-name normalized-name
         :raw-script-name raw-name
         :name-normalized? (not= raw-name normalized-name)
         :site-match site-match
         :description (get m :epupp/description)
         :run-at run-at
         :raw-run-at raw-run-at
         :run-at-invalid? (and raw-run-at
                               (not (contains? valid-run-at-values raw-run-at)))}))))

(defn create-install-button []
  (let [btn (js/document.createElement "button")]
    (set! (.-textContent btn) "Install to Epupp")
    (set! (.-className btn) "epupp-install-btn")
    (set! (.. btn -style -cssText)
          "margin: 8px 0; padding: 6px 12px; background: #2ea44f; color: white; border: 1px solid rgba(27,31,36,0.15); border-radius: 6px; font-size: 14px; font-weight: 500; cursor: pointer;")
    btn))

(defn- run-at-label
  "Format run-at value for display, with indicator for non-default timing."
  [run-at]
  (case run-at
    "document-start" "document-start (early)"
    "document-end" "document-end"
    "document-idle (default)"))

(defn show-confirmation-modal
  [manifest gist-url on-confirm on-cancel]
  (let [overlay (js/document.createElement "div")
        modal (js/document.createElement "div")
        script-name (get manifest :script-name)
        raw-name (get manifest :raw-script-name)
        name-normalized? (get manifest :name-normalized?)
        site-match (get manifest :site-match)
        description (get manifest :description)
        run-at (get manifest :run-at)
        run-at-invalid? (get manifest :run-at-invalid?)
        raw-run-at (get manifest :raw-run-at)]
    ;; Overlay styles
    (set! (.. overlay -style -cssText)
          "position: fixed; top: 0; left: 0; right: 0; bottom: 0; background: rgba(0,0,0,0.5); z-index: 9998; display: flex; align-items: center; justify-content: center;")

    ;; Modal styles
    (set! (.. modal -style -cssText)
          "background: white; padding: 24px; border-radius: 8px; max-width: 500px; box-shadow: 0 8px 24px rgba(0,0,0,0.3); z-index: 9999;")

    ;; Modal content - property table style similar to panel
    (set! (.-innerHTML modal)
          (str "<h2 style='margin-top: 0;'>Install Userscript</h2>"
               "<table style='width: 100%; border-collapse: collapse; margin-bottom: 16px;'>"
               ;; Name row
               "<tr><td style='padding: 6px 0; color: #666; width: 100px;'>Name</td>"
               "<td style='padding: 6px 0;'><code>" (or script-name "Unknown") "</code>"
               (when name-normalized?
                 (str "<br><span style='color: #888; font-size: 12px;'>Normalized from: " raw-name "</span>"))
               "</td></tr>"
               ;; Site Pattern row
               "<tr><td style='padding: 6px 0; color: #666;'>URL Pattern</td>"
               "<td style='padding: 6px 0;'>" (or site-match "<em>None</em>") "</td></tr>"
               ;; Description row
               "<tr><td style='padding: 6px 0; color: #666;'>Description</td>"
               "<td style='padding: 6px 0;'>" (or description "<em>Not specified</em>") "</td></tr>"
               ;; Run At row
               "<tr><td style='padding: 6px 0; color: #666;'>Run At</td>"
               "<td style='padding: 6px 0;'>" (run-at-label run-at)
               (when run-at-invalid?
                 (str "<br><span style='color: #d97706; font-size: 12px;'>Invalid value \"" raw-run-at "\" - using default</span>"))
               "</td></tr>"
               "</table>"
               "<p style='margin: 0 0 8px;'><strong>Source:</strong></p>"
               "<p style='margin: 0 0 16px;'><code style='word-break: break-all;'>" gist-url "</code></p>"
               "<p style='color: #666; font-size: 14px; margin-bottom: 16px;'>This will download and install the script from the gist above.</p>"
               "<div style='display: flex; gap: 8px; justify-content: flex-end;'>"
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
