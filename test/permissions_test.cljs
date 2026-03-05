(ns permissions-test
  "Tests for permissions module - host permission checking"
  (:require ["vitest" :refer [describe test expect]]
            [permissions :as permissions]))

;; ============================================================
;; Test Helpers
;; ============================================================

(defn- setup-permissions-mock!
  "Set up chrome.permissions mock with configurable contains/request behavior."
  [{:keys [contains-result request-result]}]
  (set! js/chrome.permissions
        #js {:contains (fn [_perms callback]
                         (callback (if (some? contains-result) contains-result true)))
             :request (fn [_perms callback]
                        (callback (if (some? request-result) request-result true)))}))

(defn- setup-tabs-mock!
  "Set up chrome.tabs.get mock."
  [tab]
  (set! js/chrome.tabs
        (clj->js {:get (fn [_tab-id callback]
                         (set! js/chrome.runtime.lastError nil)
                         (callback (clj->js tab)))})))

;; ============================================================
;; has-host-permission? Tests
;; ============================================================

(defn- test-has-host-permission-returns-true-when-granted []
  (setup-permissions-mock! {:contains-result true})
  (-> (permissions/has-host-permission? "https://example.com/page")
      (.then (fn [result]
               (-> (expect result) (.toBe true))))))

(defn- test-has-host-permission-returns-false-when-not-granted []
  (setup-permissions-mock! {:contains-result false})
  (-> (permissions/has-host-permission? "https://example.com/page")
      (.then (fn [result]
               (-> (expect result) (.toBe false))))))

(defn- test-has-host-permission-returns-true-when-api-unavailable []
  (set! js/chrome.permissions nil)
  (-> (permissions/has-host-permission? "https://example.com/page")
      (.then (fn [result]
               (-> (expect result) (.toBe true))))))

(describe "has-host-permission?"
  (fn []
    (test "returns true when permission is granted"
      test-has-host-permission-returns-true-when-granted)
    (test "returns false when permission is not granted"
      test-has-host-permission-returns-false-when-not-granted)
    (test "returns true when permissions API is unavailable"
      test-has-host-permission-returns-true-when-api-unavailable)))

;; ============================================================
;; has-all-urls-permission? Tests
;; ============================================================

(defn- test-has-all-urls-returns-true-when-granted []
  (setup-permissions-mock! {:contains-result true})
  (-> (permissions/has-all-urls-permission?)
      (.then (fn [result]
               (-> (expect result) (.toBe true))))))

(defn- test-has-all-urls-returns-false-when-not-granted []
  (setup-permissions-mock! {:contains-result false})
  (-> (permissions/has-all-urls-permission?)
      (.then (fn [result]
               (-> (expect result) (.toBe false))))))

(describe "has-all-urls-permission?"
  (fn []
    (test "returns true when granted"
      test-has-all-urls-returns-true-when-granted)
    (test "returns false when not granted"
      test-has-all-urls-returns-false-when-not-granted)))

;; ============================================================
;; request-host-permission! Tests
;; ============================================================

(defn- test-request-returns-true-when-granted []
  (setup-permissions-mock! {:request-result true})
  (-> (permissions/request-host-permission!)
      (.then (fn [result]
               (-> (expect result) (.toBe true))))))

(defn- test-request-returns-false-when-denied []
  (setup-permissions-mock! {:request-result false})
  (-> (permissions/request-host-permission!)
      (.then (fn [result]
               (-> (expect result) (.toBe false))))))

(defn- test-request-returns-false-when-api-unavailable []
  (set! js/chrome.permissions nil)
  (-> (permissions/request-host-permission!)
      (.then (fn [result]
               (-> (expect result) (.toBe false))))))

(describe "request-host-permission!"
  (fn []
    (test "returns true when user grants permission"
      test-request-returns-true-when-granted)
    (test "returns false when user denies permission"
      test-request-returns-false-when-denied)
    (test "returns false when permissions API is unavailable"
      test-request-returns-false-when-api-unavailable)))

;; ============================================================
;; check-tab-permission Tests
;; ============================================================

(defn- test-check-tab-returns-true-when-permitted []
  (setup-tabs-mock! {:url "https://example.com/page"})
  (setup-permissions-mock! {:contains-result true})
  (-> (permissions/check-tab-permission 1)
      (.then (fn [result]
               (-> (expect result) (.toBe true))))))

(defn- test-check-tab-returns-false-when-not-permitted []
  (setup-tabs-mock! {:url "https://example.com/page"})
  (setup-permissions-mock! {:contains-result false})
  (-> (permissions/check-tab-permission 1)
      (.then (fn [result]
               (-> (expect result) (.toBe false))))))

(defn- test-check-tab-returns-true-for-extension-urls []
  (setup-tabs-mock! {:url "chrome-extension://abc123/popup.html"})
  (-> (permissions/check-tab-permission 1)
      (.then (fn [result]
               (-> (expect result) (.toBe true))))))

(defn- test-check-tab-returns-true-for-moz-extension-urls []
  (setup-tabs-mock! {:url "moz-extension://abc123/popup.html"})
  (-> (permissions/check-tab-permission 1)
      (.then (fn [result]
               (-> (expect result) (.toBe true))))))

(defn- test-check-tab-returns-true-when-tab-has-no-url []
  (setup-tabs-mock! {})
  (-> (permissions/check-tab-permission 1)
      (.then (fn [result]
               (-> (expect result) (.toBe true))))))

(describe "check-tab-permission"
  (fn []
    (test "returns true when host permission is granted"
      test-check-tab-returns-true-when-permitted)
    (test "returns false when host permission is not granted"
      test-check-tab-returns-false-when-not-permitted)
    (test "returns true for chrome-extension:// URLs"
      test-check-tab-returns-true-for-extension-urls)
    (test "returns true for moz-extension:// URLs"
      test-check-tab-returns-true-for-moz-extension-urls)
    (test "returns true when tab has no URL"
      test-check-tab-returns-true-when-tab-has-no-url)))

;; ============================================================
;; set-permission-badge! Tests
;; ============================================================

(defn- setup-action-mock!
  "Set up chrome.action mock with spy functions."
  []
  (let [calls (atom [])]
    (set! js/chrome.action
          #js {:setBadgeText (fn [opts]
                               (swap! calls conj {:fn "setBadgeText"
                                                  :text (.-text opts)
                                                  :tabId (.-tabId opts)}))
               :setBadgeBackgroundColor (fn [opts]
                                          (swap! calls conj {:fn "setBadgeBackgroundColor"
                                                             :color (.-color opts)
                                                             :tabId (.-tabId opts)}))})
    calls))

(defn- test-set-permission-badge-sets-exclamation []
  (let [calls (setup-action-mock!)]
    (permissions/set-permission-badge! 42)
    (let [badge-call (first @calls)]
      (-> (expect (:text badge-call)) (.toBe "!"))
      (-> (expect (:tabId badge-call)) (.toBe 42)))))

(defn- test-set-permission-badge-sets-warning-color []
  (let [calls (setup-action-mock!)]
    (permissions/set-permission-badge! 42)
    (let [color-call (second @calls)]
      (-> (expect (:fn color-call)) (.toBe "setBadgeBackgroundColor"))
      (-> (expect (:color color-call)) (.toBe "#f59e0b"))
      (-> (expect (:tabId color-call)) (.toBe 42)))))

(describe "set-permission-badge!"
  (fn []
    (test "sets badge text to '!' for the given tab"
      test-set-permission-badge-sets-exclamation)
    (test "sets warning color for the given tab"
      test-set-permission-badge-sets-warning-color)))

;; ============================================================
;; clear-permission-badge! Tests
;; ============================================================

(defn- test-clear-permission-badge-clears-text []
  (let [calls (setup-action-mock!)]
    (permissions/clear-permission-badge! 42)
    (let [badge-call (first @calls)]
      (-> (expect (:text badge-call)) (.toBe ""))
      (-> (expect (:tabId badge-call)) (.toBe 42)))))

(defn- test-clear-permission-badge-only-calls-set-badge-text []
  (let [calls (setup-action-mock!)]
    (permissions/clear-permission-badge! 42)
    (-> (expect (count @calls)) (.toBe 1))
    (-> (expect (:fn (first @calls))) (.toBe "setBadgeText"))))

(describe "clear-permission-badge!"
  (fn []
    (test "clears badge text for the given tab"
      test-clear-permission-badge-clears-text)
    (test "only calls setBadgeText (not setBadgeBackgroundColor)"
      test-clear-permission-badge-only-calls-set-badge-text)))
