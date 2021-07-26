(ns billing-app.main
  "The entry point for running the server from the command line"
  (:require
    [billing-app.components.server] ;; required so that mount sees & starts it
    [billing-app.components.repl-server] ;; required so that mount sees & starts it
    [mount.core :as mount]))

(defn -main []
  (let [env (or (System/getenv "APP_ENV") "prod")]
    (println "Starting app version" (System/getenv "GIT_SHA") "env" env)
    (assert (#{"prod" "stage" "dev"} env))
    (assert (System/getenv "PORT"))
    (mount/start-with-args {:config (str "config/" env ".edn")})))
