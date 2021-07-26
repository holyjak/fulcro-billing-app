(ns billing-app.model.billing.invoice.charge
  "A single invoice line - an employee X is charged some amount for a particular service"
  (:refer-clojure :exclude [name])
  (:require
    #?@(:clj
        [[com.wsscode.pathom.connect :as pc :refer [defmutation]]
         [billing-app.components.billing-data :as billing-data]
         [billing-app.model.cache :as dev]]
        :cljs
        [[com.fulcrologic.fulcro.mutations :as m :refer [defmutation]]])
    [clojure.string :as str]
    [com.wsscode.pathom.connect :as pc]
    [com.fulcrologic.rad.attributes :as attr :refer [defattr]]
    [com.fulcrologic.rad.attributes-options :as ao]
    [com.fulcrologic.rad.report-options :as ro]))

;; A synthetic ID composed of invoice-id:sid:idx b/c RAD and Pathom require that everything has a simple ID of its own
(defattr synt-id :charge/synt-id :string
  {ao/identity? true})

(defattr service-name :charge/service-name :string
  {ao/identities #{:charge/synt-id}}) ; we could leave ao/identities since RAD auto-generated resolvers do not work with composed PK anyway

(defattr usage :charge/usage :decimal
  {ao/identities #{:charge/synt-id}})

(defattr period-from :charge/period-from :inst
  {ao/identities #{:charge/synt-id}})

(defattr period-to :charge/period-to :inst
  {ao/identities #{:charge/synt-id}})

(defattr included?
  "True if the charge is included in the resulting usage, false if it is ignored due to business rules."
  :charge/included? :boolean
  {ao/identities #{:charge/synt-id}})

(defn charge-view [invoice-id idx
                   {:kd/keys               [sid usage-inc-vat]
                    :keys                  [period serviceType chargeType]
                    {:keys [invoice name]} :debug
                    :as                    charge}]
      (merge
        (select-keys charge [:kd/charge-type])
        {:charge/synt-id      (clojure.string/join ":" [invoice-id sid idx])
         :charge/invoice      invoice
         :charge/included?    (:billing-app.components.billing-data/included? charge)
         :charge/charge-type  chargeType
         :charge/service-name name
         :charge/service-type serviceType
         :charge/usage        usage-inc-vat
         :charge/period-from  (:billing/startDate period)
         :charge/period-to    (:billing/endDate period)}))

#?(:clj
   (pc/defresolver invoice-employee-charges
     [env _]
     {::pc/input  #{}
      ::pc/output [{:invoice-employee-charges
                    [:charge/synt-id
                     :charge/invoice
                     :charge/charge-type
                     :charge/service-name
                     :charge/service-type
                     :charge/usage
                     :charge/period-from
                     :charge/period-to
                     :charge/included?
                     ;; Extra props:
                     :kd/charge-type]}]}
     (let [ds      (get-in env [:com.fulcrologic.rad.database-adapters.sql/connection-pools :billing])
           {invoice-id :invoice/id
            sid         :br-employee/employee-id}
           (:query-params env)
           _       (do (assert invoice-id) (assert sid))
           batches (->> (dev/caching #'billing-data/employee-batches ds invoice-id sid)
                        (apply concat)
                        (map-indexed (partial charge-view invoice-id))
                        (remove (comp zero? :charge/usage))
                        (sort-by :charge/usage)
                        (reverse)
                        vec)]
       {:invoice-employee-charges batches})))

(def attributes [synt-id service-name usage period-from period-to included?])
