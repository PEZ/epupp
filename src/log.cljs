(ns log
  "Epupp console logging with consistent prefix format.

   Format: [Epupp:Subsystem] message

   All prefixes start with 'Epupp:' for easy filtering (grep '[Epupp:')
   Subsystem is a colon-separated string, e.g. 'Background:WS'.

   Debug logging is gated by the !debug-enabled? atom, controlled via
   the 'settings/debug-logging' storage key in Epupp settings.")

(def !debug-enabled?
  "Atom controlling whether debug-level log messages are emitted.
   Set via set-debug-enabled! when the storage setting changes."
  (atom false))

(defn set-debug-enabled!
  "Set whether debug logging is enabled."
  [enabled?]
  (reset! !debug-enabled? enabled?))

(defn log
  "Log message with subsystem prefix. Format: [Epupp:Subsystem] message"
  [level subsystem & messages]
  (let [prefix (str "[Epupp:" subsystem "]")]
    (apply (case level
             :debug js/console.debug
             :info js/console.info
             :warn js/console.warn
             :error js/console.error
             js/console.log)
           prefix messages)))

(defn debug
  "Log debug message. Only emits if debug logging is enabled in settings."
  [subsystem & messages]
  (when @!debug-enabled?
    (apply log :debug subsystem messages)))

(defn info
  "Log info message. Always emits."
  [subsystem & messages]
  (let [prefix (str "[Epupp:" subsystem "]")]
    (apply js/console.info prefix messages)))

(defn warn
  "Log warning message. Always emits."
  [subsystem & messages]
  (let [prefix (str "[Epupp:" subsystem "]")]
    (apply js/console.warn prefix messages)))

(defn error
  "Log error message. Always emits."
  [subsystem & messages]
  (let [prefix (str "[Epupp:" subsystem "]")]
    (apply js/console.error prefix messages)))
