(ns billing-app.components.config
  (:require
    [com.fulcrologic.fulcro.server.config :as fserver]
    [billing-app.lib.logging :as logging]
    [mount.core :refer [defstate args]]
    [taoensso.timbre :as log]))

(defstate config
  "The overrides option in args is for overriding
   configuration in tests."
  :start (let [{:keys [config overrides]
                :or   {config "config/dev.edn"}} (args)
               loaded-config (merge (fserver/load-config! {:config-path config}) overrides)]
           (log/info "Loading config" config)
           (logging/configure-logging! loaded-config)
           (when (-> loaded-config :aws :fake-credentials)
             ;; The Cognitect AWS client checks now that some AWS credentials are available;
             ;; locally we don't need them to talk to the local DynamoDB so we set fakes to fool it
             (println "Faking AWS credentials...")
             (System/setProperty "aws.accessKeyId" "fake")
             (System/setProperty "aws.secretKey" "fake"))
           loaded-config))


