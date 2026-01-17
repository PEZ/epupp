(ns bg-fs-dispatch
  "Uniflow dispatch for background FS operations.
   Bridges pure action handlers with storage side effects."
  (:require [background-actions :as bg-actions]
            [storage :as storage]
            [log :as log]))

(defn- perform-fs-effect!
  "Execute FS effects. Called by dispatch-fs-action! after pure handler runs."
  [send-response [effect & args]]
  (log/info "Background" nil "perform-fs-effect! START:" effect)
  (let [start (.now js/Date)]
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
        (log/info "Background" nil "Sending response:" response-data)
        (send-response (clj->js response-data)))

      (log/warn "Background" nil "Unknown FS effect:" effect))
    (let [elapsed (- (.now js/Date) start)]
      (when (> elapsed 50)
        (log/warn "Background" nil "SLOW effect" effect "took" elapsed "ms")))))

(defn dispatch-fs-action!
  "Dispatch an FS action through pure handler, then execute effects.
   Bridges the pure Uniflow pattern with storage side effects."
  [send-response action]
  (log/info "Background" nil "dispatch-fs-action! START:" (first action))
  (try
    (let [start (.now js/Date)
          state {:storage/scripts (storage/get-scripts)}
          _ (log/info "Background" nil "Got scripts, count:" (count (:storage/scripts state)))
          uf-data {:system/now (.now js/Date)}
          result (bg-actions/handle-action state uf-data action)
          _ (log/info "Background" nil "Handler result keys:" (keys result))
          {:uf/keys [db fxs]} result
          _ (log/info "Background" nil "Effects to execute:" (count fxs) (mapv first fxs))]
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
          (perform-fs-effect! send-response fx)))
      (let [elapsed (- (.now js/Date) start)]
        (log/info "Background" nil "dispatch-fs-action! DONE:" (first action) "in" elapsed "ms")
        (when (> elapsed 100)
          (log/error "Background" nil "SLOW dispatch" (first action) "took" elapsed "ms"))))
    (catch :default e
      (log/error "Background" nil "dispatch-fs-action! ERROR:" e)
      (send-response #js {:success false :error (str "Dispatch error: " (.-message e))}))))
