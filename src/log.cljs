(ns log
  "Epupp console logging with consistent prefix format.
   
   Format: [Epupp:Module] or [Epupp:Module:Context]
   
   All prefixes start with 'Epupp:' for easy filtering (grep '[Epupp:')
   Module name matches source file/namespace.
   Context is optional, for logical sub-areas within a module.")

(defn log
  "Log to console with Epupp prefix.
   level: :log | :warn | :error
   module: string (Background, Storage, Panel, etc.)
   context: string or nil (WS, Inject, Approval, etc.)"
  [level module context & args]
  (let [prefix (if context
                 (str "[Epupp:" module ":" context "]")
                 (str "[Epupp:" module "]"))]
    (apply (case level
             :log js/console.log
             :warn js/console.warn
             :error js/console.error
             js/console.log)
           prefix args)))

(defn info
  "Log info message. Shorthand for (log :log module context ...)"
  [module context & args]
  (apply log :log module context args))

(defn warn
  "Log warning. Shorthand for (log :warn module context ...)"
  [module context & args]
  (apply log :warn module context args))

(defn error
  "Log error. Shorthand for (log :error module context ...)"
  [module context & args]
  (apply log :error module context args))
