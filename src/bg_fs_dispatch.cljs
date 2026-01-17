(ns bg-fs-dispatch
  "Uniflow dispatch for background FS operations.
   Bridges pure action handlers with storage side effects."
  (:require [background-actions :as bg-actions]
            [storage :as storage]
            [log :as log]))

(defn- perform-fs-effect!
  "Execute FS effects. Called by dispatch-fs-action! after pure handler runs."
  [send-response [effect & args]]
  (case effect
    :storage/fx.persist!
    (let [[new-scripts] args]
      ;; Update storage atom and persist
      (swap! storage/!db assoc :storage/scripts new-scripts)
      (storage/persist!))

    :bg/fx.broadcast-scripts-changed!
    nil ; Storage onChanged listener handles UI updates automatically

    :bg/fx.send-response
    (let [[response-data] args]
      (send-response (clj->js response-data)))

    (log/warn "Background" nil "Unknown FS effect:" effect)))

(defn dispatch-fs-action!
  "Dispatch an FS action through pure handler, then execute effects.
   Bridges the pure Uniflow pattern with storage side effects."
  [send-response action]
  (let [state {:storage/scripts (storage/get-scripts)}
        uf-data {:system/now (.now js/Date)}
        result (bg-actions/handle-action state uf-data action)
        {:uf/keys [db fxs]} result]
    ;; Execute effects
    (doseq [fx fxs]
      (case (first fx)
        :storage/fx.persist!
        (when db
          ;; Pass the new scripts from db to the effect
          (perform-fs-effect! send-response [:storage/fx.persist! (:storage/scripts db)]))

        :bg/fx.send-response
        (perform-fs-effect! send-response fx)

        ;; Other effects pass through
        (perform-fs-effect! send-response fx)))))
