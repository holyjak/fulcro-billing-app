(ns billing-app.model.billing.invoice
  (:refer-clojure :exclude [name])
  (:require
    #?@(:clj
        [[com.wsscode.pathom.connect :as pc :refer [defmutation]]
         [billing-app.components.database-queries :as queries]
         [billing-app.components.billing-data :as billing-data]
         [billing-app.model.cache :as dev]]
        :cljs
        [[com.fulcrologic.fulcro.mutations :as m :refer [defmutation]]])
    [billing-app.formatters.date-time :as fmt.date-time]
    [com.wsscode.pathom.connect :as pc]
    [com.fulcrologic.rad.attributes :refer [defattr]]
    [com.fulcrologic.rad.attributes-options :as ao]
    [com.fulcrologic.rad.report-options :as ro])
  #?(:clj  (:import
             (java.time LocalDateTime ZoneId Instant)
             (java.time.format DateTimeFormatter)
             (java.util Date))
     :cljs (:import
             goog.i18n.DateTimeFormat)))

(defattr invoice-id :invoice/id :decimal
         {ao/identity? true
          ao/schema    :billing
          :com.fulcrologic.rad.database-adapters.sql/table  "INVOICE"
          #_#_:com.fulcrologic.rad.database-adapters.sql/column-name "id"})

(defattr billing-date :invoice/billing-date :inst
         {ao/identities        #{:invoice/id}
          ao/schema            :billing})

(defattr invoice-period-from :invoice/invoice-period-from :inst {ao/identities #{:invoice/id}, ao/schema :billing})

(defattr invoice-period-to :invoice/invoice-period-to :inst {ao/identities #{:invoice/id}, ao/schema :billing})

#?(:clj
    (defn months-between-approx
      "An approximate number of months between the two instants."
      ^double [^Instant i1 ^Instant i2]
      (/ (.toDays (java.time.Duration/between i1 i2)) 30.5)))

(defattr invoice-period-months :invoice/invoice-period-months :int
  {ao/pc-input   #{:invoice/invoice-period-from :invoice/invoice-period-to}
   ao/pc-output  [:invoice/invoice-period-months]
   ao/pc-resolve (fn [_ {:invoice/keys [invoice-period-from invoice-period-to]}]
                   #?(:clj
                      {:invoice/invoice-period-months
                       (when (and invoice-period-from invoice-period-to)
                         (Math/round
                           (months-between-approx
                             (fmt.date-time/->instant invoice-period-from)
                             (fmt.date-time/->instant invoice-period-to))))}))})

;; period is derived from invoice period from & to
(defattr period :invoice/period :string
  {ao/identities #{:invoice/id}
   :mb/description "Startdato av den yngste - sluttdato av den eldste inkluderte fakturalinje. Dette kan bli lengre en billing periode f.eks. pga carry-over."})

(defattr timestamp :invoice/timestamp :inst
  {ao/identities #{:invoice/id}
   ao/schema :billing
   ro/column-heading "Uploaded ts"})

(defattr no-usage-data :invoice/no-usage-data :boolean {ao/identities #{:invoice/id}, ao/schema :billing})

(defattr notification-sent :invoice/notification-sent :boolean
  {ao/identities #{:invoice/id}
   ao/schema :billing
   ro/column-heading "Notified?"
   :mb/description "Kostnadsrapporter ble sendt via e-post"})

(defattr created-date :invoice/created-date :inst
  {ao/identities        #{:invoice/id}
   ao/schema            :billing
   :mb/description "Dagen når fakturaen ble behandlet"})

(defattr cache-done :invoice/cache-done :boolean
  {ao/identities #{:invoice/id}
   ao/schema :billing
   ro/column-heading "Report?"
   :mb/description "Har rapporter blitt generert? (Genereres automatisk, vanligvis innenfor én time, during business hours.)"})

(defattr valid :invoice/valid :boolean
  {ao/identities #{:invoice/id}
   ao/schema :billing
   ro/column-heading "Valid?"
   :mb/description "Var det noen problemer som f.eks. manglede ansattid?"})

(defattr employee-notification-sent :invoice/employee-notification-sent :boolean
  {ao/identities #{:invoice/id}
   ao/schema :billing
   ro/column-heading "Employees notified?"
   :mb/description "SMS/epost med oppsummering sendt til ansatte, om skrudd på"})

(defattr nr-employees :invoice/nr-employees :decimal
  {ao/identities #{:invoice/id}
   ro/column-heading "Nr. employees"})

(defn derive-period [{from :invoice/invoice-period-from
                      to :invoice/invoice-period-to
                      :as invoice}]
  (assoc invoice :invoice/period (fmt.date-time/format-period from to)))

#?(:clj
   (pc/defresolver latest-invoice
     "The latest invoice of the org

     For use in manual `load!` where the org.nr. is provided explicitly."
     [env {:organization/keys [organization-number]}]
     {::pc/input  #{:organization/organization-number}
      ::pc/output [{:organization/latest-invoice
                    [:invoice/id
                     :invoice/billing-date
                     :invoice/invoice-period-from :invoice/invoice-period-to
                     :invoice/period
                     :invoice/timestamp :invoice/no-usage-data :invoice/notification-sent
                     :invoice/created-date :invoice/cache-done :invoice/valid
                     :invoice/employee-notification-sent
                     :invoice/nr-employees
                     :invoice/nr-invoices]}]}
     {:organization/latest-invoice
      (-> (dev/caching #'queries/get-latest-invoices env organization-number)
          first
          derive-period)}))

(comment
  ((::pc/resolve latest-invoice)
   (development/get-jdbc-datasource-in-env)
   {:organization/organization-number "123456789"})

  (dev/caching #'queries/get-latest-invoices (development/get-jdbc-datasource-in-env) "123456789")
  (dev/caching #'queries/get-latest-organizations (development/get-jdbc-datasource-in-env) nil)

  (dev/evict-all-for #'queries/get-latest-invoices)
  ,)

#?(:clj
   (pc/defresolver latest-invoices-parametrized
     "Similar to [latest-invoices] but the org.nr. is supplied via parameters
     (so that it can be used from a RAD report)
     and returns a few of those."
     [{:keys [query-params] :as env} {:organization/keys []}]
     {::pc/input  #{}
      ::pc/output [{:organization/latest-invoices
                    [:invoice/id
                     :invoice/billing-date
                     :invoice/invoice-period-from :invoice/invoice-period-to
                     :invoice/period
                     :invoice/timestamp :invoice/no-usage-data :invoice/notification-sent
                     :invoice/created-date :invoice/cache-done :invoice/valid
                     :invoice/employee-notification-sent
                     :invoice/nr-employees
                     :invoice/nr-invoices]}]}
     ;; TODO Find out why sometimes we have :route-params sometimes not, unify
     {:organization/latest-invoices
      (->> (dev/caching #'queries/get-latest-invoices env
                        (or (-> query-params :route-params :organization/organization-number)
                            (:organization/organization-number query-params)))
           (map derive-period))}))

#?(:clj
   (pc/defresolver invoice-parts-too-long
     "Invoice parts with period longer than the org's billing period"
     [env {:invoice/keys [id]}]
     {::pc/input  #{:invoice/id}
      ::pc/output [{:invoice/invoice-parts-too-long
                    [:invoice-part/synt-id
                     :invoice-part/number
                     :invoice-part/period
                     :invoice-part/accid]}]}
     {:invoice/invoice-parts-too-long
      (->> (dev/caching #'billing-data/find-invoice-parts-too-long
                        (get-in env [:com.fulcrologic.rad.database-adapters.sql/connection-pools :billing])
                        id))}))

(def attributes [invoice-id
                 billing-date
                 cache-done
                 timestamp
                 created-date
                 invoice-period-from
                 invoice-period-to
                 invoice-period-months
                 period
                 no-usage-data
                 notification-sent
                 nr-employees
                 employee-notification-sent
                 valid])


#?(:clj
   (def resolvers [latest-invoice latest-invoices-parametrized
                   invoice-period-months
                   invoice-parts-too-long]))
