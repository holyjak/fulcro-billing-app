(ns billing-app.components.parser
  (:require
    [billing-app.model :refer [all-attributes all-resolvers]]
    [billing-app.components.auto-resolvers :refer [automatic-resolvers]]
    [billing-app.components.config :refer [config]]
    [billing-app.components.connection-pools :as pools]
    [com.fulcrologic.rad.database-adapters.sql.plugin :as sql]
    [com.fulcrologic.rad.pathom :as pathom
     :refer [process-error preprocess-parser-plugin log-request! elide-reader-errors post-process-parser-plugin-with-env log-response! query-params-to-env-plugin]]
    [com.fulcrologic.rad.form :as form]
    [com.fulcrologic.rad.blob :as blob]
    [billing-app.components.blob-store :as bs]
    [billing-app.components.save-middleware :as save]
    [billing-app.components.delete-middleware :as delete]
    [mount.core :refer [defstate]]
    [billing-app.model.billing :as billing]
    [com.fulcrologic.rad.attributes :as rad.attr]
    [com.wsscode.common.async-clj :refer [<?]]
    [com.wsscode.pathom.connect :as pc]
    [com.wsscode.pathom.core :as p]
    [com.wsscode.pathom.connect.graphql2 :as pcg]
    [mount.core :as mount]
    [clojure.spec.alpha :as s]
    [billing-app.model.cache :as dev]
    [taoensso.timbre :as log]))

(pc/defresolver index-explorer [env _]
                {::pc/input  #{:com.wsscode.pathom.viz.index-explorer/id}
                 ::pc/output [:com.wsscode.pathom.viz.index-explorer/index]}
                {:com.wsscode.pathom.viz.index-explorer/index
                 (get env ::pc/indexes)})

(defn validate-connect-key-reader ; TODO Verify this actually works with our older version of Pathom
  "Print useful warnings when a query contains unknown keys"
  [env]
  (let [k         (-> env :ast :key)
        index-oir (-> env ::pc/indexes ::pc/index-oir)]
    (when (and (not (p/ident? k))
               ;(not (p/placeholder-key? env k))
               (not (contains? index-oir k)))
      (println (str "WARN: trying to read key " k " which is not available in the index.")))
    ::p/continue))

(defonce indexes (atom {}))

;;----------------------------------------------------------------------------- pathom utils
(defn raise-errors-preserving [data]
  "Like `com.wsscode.pathom.core/raise-errors` but also keeps the errors at the top level"
  (reduce
    (fn [m [path err]]
      (if (= ::p/reader-error (get-in m path))
        (let [path' (concat (butlast path) [:com.wsscode.pathom.core/errors (last path)])]
          (assoc-in m path' err))
        m))
    data                                                    ; WAS: (dissoc data :com.wsscode.pathom.core/errors)
    (get data :com.wsscode.pathom.core/errors)))

;;----------------------------------------------------------------------------- copied from com.fulcrologic.rad.pathom

(defn parser-args [{:com.fulcrologic.rad.pathom/keys [trace? log-requests? log-responses? log-unknown-keys?] :as config} plugins resolvers]
  {::p/mutate  pc/mutate
   ::p/env     {::p/reader               (cond-> [p/map-reader pc/reader2
                                                  pc/index-reader
                                                  pc/open-ident-reader p/env-placeholder-reader]
                                                 log-unknown-keys? (conj validate-connect-key-reader)) ; <-- MB added
                ::p/placeholder-prefixes #{">"}}
   ::p/plugins (into []
                     (keep identity
                               (concat
                                 [(pc/connect-plugin {::pc/register resolvers
                                                      ;; ME - added (so that we can integrate with GraphQL sources & add them there):
                                                      ::pc/indexes  indexes})]
                                 plugins
                                 [(p/env-plugin {::p/process-error process-error})
                                  (when log-requests? (preprocess-parser-plugin log-request!))
                                  ;; TODO: Do we need this, and if so, we need to pass the attribute map
                                  ;(p/post-process-parser-plugin add-empty-vectors)
                                  query-params-to-env-plugin
                                  p/error-handler-plugin
                                  (p/post-process-parser-plugin p/elide-not-found)
                                  ;; raise-errors must be *after* elide-not-found and *before* elide-reader-errors
                                  (p/post-process-parser-plugin #'raise-errors-preserving)
                                  (p/post-process-parser-plugin elide-reader-errors)
                                  (when log-responses? (post-process-parser-plugin-with-env log-response!))
                                  (when trace? p/trace-plugin)])))})

(defn new-parser
  "Create a new pathom parser. `config` is a map containing a ::config key with parameters
  that affect the parser. `extra-plugins` is a sequence of pathom plugins to add to the parser. The
  plugins will typically need to include plugins from any storage adapters that are being used,
  such as the `datomic/pathom-plugin`.
  `resolvers` is a vector of all of the resolvers to register with the parser, which can be a nested collection.

  Supported config options under the ::config key:

  - `:trace? true` Enable the return of pathom performance trace data (development only, high overhead)
  - `:log-requests? boolean` Enable logging of incoming queries/mutations.
  - `:log-responses? boolean` Enable logging of parser results."
  [config extra-plugins resolvers]
  (let [real-parser (p/parser (parser-args config extra-plugins resolvers))
        {:keys [trace?]} (get config :com.fulcrologic.rad.pathom/config {})]
    (fn wrapped-parser [env tx]
      (real-parser env (if trace?
                         (conj tx :com.wsscode.pathom/trace)
                         tx)))))
;;-----------------------------------------------------------------------------
;(defn my-debug-plugin [ks]
;  {::p/wrap-parser
;   (fn [parser]
;     (println "Installing the debug plugin, watch your tap> receiver for info...")
;     (fn [env tx]
;       (tap> ["parse env" (select-keys env ks) "tx" tx])
;       (parser env tx)))
;   ::p/wrap-read
;   (fn [reader]
;     (fn [env]
;       (let [res (reader env)]
;         (tap> ["read =>" (select-keys env ks)])
;         res)))
;   ::pc/wrap-resolve
;   (fn [resolve]
;     (fn [env input]
;       (let [res (resolve env input)]
;         (tap> ["resolve =>" (select-keys env ks)])
;         res)))})

(defstate parser
  :start
          (new-parser config
                      [#_(my-debug-plugin [:com.wsscode.pathom.connect.graphql2/errors
                                           :com.wsscode.pathom.core/errors*])
                       (rad.attr/pathom-plugin all-attributes)
                       (form/pathom-plugin save/middleware delete/middleware)
                       (sql/pathom-plugin (fn [_] {:production (:main pools/connection-pools)
                                                   :billing    (:billing pools/connection-pools)}))
                       (blob/pathom-plugin bs/temporary-blob-store {:files         bs/file-blob-store
                                                                    :avatar-images bs/image-blob-store})]
                      [automatic-resolvers
                       form/resolvers
                       (blob/resolvers all-attributes)
                       all-resolvers]))

;; BYPASS Spec errors due to Fulcro RAD producing weird stuff
(s/def :com.wsscode.pathom.connect/output any?)

(comment
  (parser
    (development/get-jdbc-datasource-in-env)
    [{[:organization/organization-number "123456789"]
      [:organization/organization-number
       :organization/current-bill-cycle-length-months
       :organization/validation-errors
       :invoice-error/latest-error
       {:organization/latest-invoice
        [:invoice/id
         :invoice/billing-date
         :invoice/period
         :invoice/timestamp
         :invoice/created-date
         :invoice/cache-done
         :invoice/valid
         :invoice/nr-employees
         :invoice/nr-invoices
         :invoice/invoice-period-months]}]}])

  (parser
    (development/get-jdbc-datasource-in-env)
    [{[:invoice/id 876822M]
      [{:invoice/invoice-parts-too-long [:invoice-part/synt-id]}]}])

  nil)
