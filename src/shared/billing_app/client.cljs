(ns billing-app.client
  (:require
    [cljs.spec.alpha :as s]
    [clojure.string :as str]
    [billing-app.formatters.date-time :as fmt.date-time]
    [billing-app.ui :as mb-ui]
    [billing-app.ui.billing.ui :as ksd-ui]
    ;[billing-app.ui.mutations :as mutations]
    [billing-app.ui-utils.rad-controls :as mb.controls]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.mutations :as m]
    [com.fulcrologic.fulcro.data-fetch :as df]
    [com.fulcrologic.fulcro.rendering.keyframe-render2 :as kfr2]
    [com.fulcrologic.rad :as rad]
    [com.fulcrologic.rad.application :as rad-app]
    [com.fulcrologic.rad.rendering.semantic-ui.semantic-ui-controls :as sui]
    [com.fulcrologic.rad.report :as report]
    [com.fulcrologic.rad.type-support.decimal :as math]
    [com.fulcrologic.rad.type-support.date-time :as datetime]
    [com.fulcrologic.fulcro.algorithms.timbre-support :refer [console-appender prefix-output-fn]]
    [taoensso.timbre :as log]
    [taoensso.tufte :as tufte :refer [profile]]
    [com.fulcrologic.rad.type-support.date-time :as datetime]
    [com.fulcrologic.rad.routing.html5-history :as hist5 :refer [html5-history]]
    [com.fulcrologic.rad.routing.history :as history]
    [com.fulcrologic.fulcro.components :as comp]
    [com.wsscode.pathom.core]
    [edn-query-language.core :as eql]
    [com.fulcrologic.fulcro.routing.dynamic-routing :as dr]
    [com.fulcrologic.rad.routing :as routing]))

(defonce stats-accumulator
  (tufte/add-accumulating-handler! {:ns-pattern "*"}))

; {:billing/all-organizations nil, :com.wsscode.pathom.core/errors {[:billing/all-organizations] #:com.fulcrologic.rad.pathom{:errors {:message "No MB DS in env!", :data {:mb-pools nil}}}}}
(defn target-component-requests-errors [query path]
  (some->> (when (vector? path) (butlast path)) ; path can be a single keyword -> ignore
           (get-in query)
           meta
           :component
           (comp/get-query)
           (some #{:com.wsscode.pathom.core/errors})))

(defn extract-query-from-transaction
  "Extract the component query from a `result`.
  Ex. tx.: `[({:all-organizations [:orgnr ...]} params) ::p/errors]`,
  `[{:people [:orgnr ...]} ::p/errors]`"
  [original-transaction]
  (let [query (first original-transaction)]
    (cond-> query
            ;; A parametrized query is wrapped in (..) but we need the raw data query itself
            (list? query) (first))))

(defn unhandled-errors
  "Returns Pathom errors (if any) that are not handled by the target component

  The argument is the same one as supplied to Fulcro's `remote-error?`"
  [result]
  ;; TODO Handle RAD reports - their query is `{:some/global-resolver ..}` and it lacks any metadata
  (let [load-errs (:com.wsscode.pathom.core/errors (:body result))
        query (extract-query-from-transaction (:original-transaction result))
        mutation-sym (as-> (-> query keys first) x
                           (when (sequential? x) (first x))
                           (when (symbol? x) x)) ; join query => keyword
        mutation-errs (when mutation-sym
                        (get-in result [:body mutation-sym :com.fulcrologic.rad.pathom/errors]))]
    #_(when (seq load-errs)
        (def *result result)
        (tap> [:unhandled-errors result]))
    ;; Example of a mutation:
    ;; BODY = {billing-app.model.billing/simulate-invoice {:com.fulcrologic.rad.pathom/errors {:message "..."}}
    ;; QUERY = {(billing-app.model.billing/simulate-invoice ..) ...}
    ;; Example of a load (here, a join query):
    ;; QUERY = {[:organization/organization-number "93123489"] [:organization/organization-number ...]}
    ;; BODY = {[:organization/organization-number "93123489"] {...}, ::p/errors {[[:organization/organization-number "910431714" :invoice-error/latest-error] {:com.fulcrologic.rad.pathom/errors {..}}}
    ;; Another example, for a report loading from the source attr. `:billing/all-organizations`:
    ;; QUERY = {:billing/all-organizations [...]}
    (cond
      (seq load-errs)
      (reduce
        (fn [unhandled-errs [path :as entry]]
          (if (target-component-requests-errors query path)
            (do
              (log/info "unhandled-errors: Ignoring error for" (last path) ", handled by the requesting component")
              unhandled-errs)
            (conj unhandled-errs entry)))
        {}
        ;; errors is a map of `path` to error details
        load-errs)

      mutation-errs
      mutation-errs

      :else
      nil)))

(comment

  (-> *result :original-transaction first list?)
  (-> *result :body keys)
  (unhandled-errors *result)

  nil)

(defn contains-error? [result]
  (seq (unhandled-errors result)))

(defn component-handles-mutation-errors? [component]
  (boolean (some-> component comp/get-query set ::m/mutation-error)))

(defn global-error-action
  "Run when app's :remote-error? returns true"
  [{:keys [app component state], {:keys [body status-code status-text error-text]} :result :as env}]
  ;; env has app, component, ref (component ident), result, state, :mutation-ast, etc.
  (when-not (component-handles-mutation-errors? component)
    (let [pathom-errs (:com.wsscode.pathom.core/errors body)
          msg (cond
                (string? error-text)
                (cond-> error-text ; ex.: 'Forbidden [403]' in the case of http-code 403
                         (and status-code (> status-code 299))
                         (str " - " body))

                pathom-errs
                (->> pathom-errs
                     (map (fn [[query {{:keys [message data]} :com.fulcrologic.rad.pathom/errors :as val}]]
                            (str query
                                 " failed with "
                                 (or (and message (str message (when (seq data) (str ", extra data: " data))))
                                     val))))

                     (str/join " | "))

                :else
                (str body))]
      (swap! state assoc :ui/global-error msg))))

(m/defmutation ui-ready
  "Mutation. Called at the end of [[init]], after `dr/initialize!` and thus 'executed' after all relevant routers have been started"
  [_]
  (action [{:keys [state]}]
          (swap! state assoc :ui/ready? true)
          (log/info "UI ready!")))

(defn restore-route-ensuring-leaf!
  "Attempt to restore the route given in the URL. If that fails, simply route to the default given (a class and map).
   WARNING: This should not be called until the HTML5 history is installed in your app.
   (Based on `hist5/restore-route!` modified to check for Partial Routes and routing to the correct leaf target.)

   NOTE: Fulcro dyn. routing requires that you always route to a leaf target, i.e. not just to a router somewhere in
   the middle of your UI tree with some unrouted, descendant routers - otherwise weird stuff may happen."
  [app]
  (let [{:keys [route params]} (hist5/url->route)
        target0 (dr/resolve-target app route)
        target  (condp = target0
                  nil mb-ui/Intro ; the default if nothing in the URL
                  ;; Old partial routes - replace them with full paths to the correct leaf target:
                  ksd-ui/Billing ksd-ui/OrgList ; the default leaf target for /kost...
                  ksd-ui/OrgDashboard ksd-ui/LatestInvoiceList
                  ;; None of the above matched so this is likely already a leaf target:
                  target0)]
    (routing/route-to! app target (or params {}))))

(when goog.DEBUG
  (set! js/holyjak.fulcro_troubleshooting._STAR_config_STAR_
        {:join-prop-filter (fn [component-instance prop] (not= prop  :ui/details-popup))}))

(defonce app (rad-app/fulcro-rad-app
               {:client-did-mount    (fn [app]
                                       (log/merge-config! {:output-fn prefix-output-fn
                                                           :appenders {:console (console-appender)}})
                                       (restore-route-ensuring-leaf! app))
                :remote-error?       (fn [result]
                                       (or
                                         (app/default-remote-error? result)
                                         (contains-error? result)))
                ;; :default-result-action! com.fulcrologic.fulcro.mutations/default-result-action!
                :global-error-action global-error-action
                ;; Troubleshooting - see https://github.com/holyjak/fulcro-troubleshooting
                :render-middleware (when goog.DEBUG js/holyjak.fulcro_troubleshooting.troubleshooting_render_middleware)}))


(defn deep-merge [& xs]
  "Merges nested maps without overwriting existing keys."
  (if (every? map? xs)
    (apply merge-with deep-merge xs)
    (last xs)))

(defn setup-RAD []
  (rad-app/install-ui-controls!
    app
    (deep-merge sui/all-controls mb.controls/all-controls))
  (report/install-formatter! app :inst :default fmt.date-time/date-formatter)
  (report/install-formatter! app :decimal :default (fn [_ v] ; because some of our decimals are actually ints (namely 0)
                                                     (if (math/bigdecimal? v)
                                                       (math/numeric->str v)
                                                       (str v))))
  (report/install-formatter! app :boolean :default (fn [_ v]
                                                     (let [b (cond
                                                               (math/bigdecimal? v) (math/= v 1)
                                                               (number? v) (= v 1)
                                                               :else (boolean v))]
                                                       (if b
                                                         "Ja"
                                                         "Nei")))))

(defn refresh []
  ;; hot code reload of installed controls
  (log/info "Reinstalling controls")
  (setup-RAD)
  (comp/refresh-dynamic-queries! app)
  (app/mount! app mb-ui/Root "app"))

(defn init []
  (log/info "Starting App")
  ;; a default tz until they log in
  (datetime/set-timezone! "Europe/Oslo")
  (app/set-root! app mb-ui/Root {:initialize-state? true})
  (dr/initialize! app)
  (history/install-route-history! app (html5-history))
  (setup-RAD)

  (dr/change-route! app (dr/path-to mb-ui/Intro)) ; overridden in app's on-mount but this makes sure the root router is initialized
  (comp/transact! app [(ui-ready)])

  (app/mount! app mb-ui/Root "app" {:initialize-state? false})
  #_(uism/begin! app report/report-machine [:component/id :OrgList] {:actor/report mb-ui/OrgList}))

(defonce performance-stats (tufte/add-accumulating-handler! {}))

(defn pperf
  "Dump the currently-collected performance stats"
  []
  (let [stats (not-empty @performance-stats)]
    (println (tufte/format-grouped-pstats stats))))

;; Workaround for https://github.com/fulcrologic/fulcro-rad/issues/32
;(s/def :edn-query-language.ast/node any?)
(defmethod edn-query-language.core/node-type :join [_]
  (s/keys :req-un [:edn-query-language.ast/type :edn-query-language.ast/key :edn-query-language.ast/dispatch-key :edn-query-language.ast/query] :opt-un [:edn-query-language.ast/children]))
