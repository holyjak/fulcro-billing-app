(ns billing-app.components.repl-server
  (:require
    [mount.core :refer [defstate]]
    [taoensso.timbre :as log]
    [billing-app.components.config :refer [config]]
    [billing-app.components.ring-middleware :refer [middleware]]
    [nrepl.server :as nrepl]))

(defstate repl-server
  :start
  (try
    (let [port           (get-in config [:repl :port])
          running-server (nrepl/start-server :port port :bind "0.0.0.0")]
      (log/info "Starting REPL server at port" port)
      {:server running-server})
    (catch Exception e
      (log/info "Failed to start nREPL server: " (ex-message e))
      {:server nil}))
  :stop
  (let [{:keys [server]} repl-server]
    (when server
      (nrepl/stop-server server))))
