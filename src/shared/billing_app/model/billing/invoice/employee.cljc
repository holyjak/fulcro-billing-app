(ns billing-app.model.billing.invoice.employee
  (:refer-clojure :exclude [name])
  (:require
    #?@(:clj
        [[com.wsscode.pathom.connect :as pc :refer [defmutation]]
         [billing-app.components.database-queries :as queries]
         [billing-app.model.cache :as dev]]
        :cljs
        [[com.fulcrologic.fulcro.mutations :as m :refer [defmutation]]])
    [com.wsscode.pathom.connect :as pc]
    [com.fulcrologic.rad.attributes :as attr :refer [defattr]]
    [com.fulcrologic.rad.attributes-options :as ao]
    [billing-app.utils :as utils]))

(defattr invoice-id :br-employee/invoice-id :int {})

(defattr employee-id :br-employee/employee-id :string {})

(defattr account-id :br-employee/account-id :string {})

(defattr total-usage :br-employee/total-usage-inc-vat :decimal {})

#?(:clj
   (pc/defresolver invoice-employees [env {:invoice/keys [id]}]
     {::pc/input  #{:invoice/id}
      ::pc/output [{:invoice/employees
                    [:br-employee/employee-id
                     :br-employee/account-id,
                     :br-employee/total-usage-inc-vat]}]}
     {:invoice/employees
      (dev/caching #'queries/get-invoice-employees env id)}))

#?(:clj
   (pc/defresolver invoice-employees-parametrized
     [{{invoice-id :invoice/id, sid-part :br-employee/employee-id} :query-params
       :as env}
      _]
     {::pc/input  #{}
      ::pc/output [{:invoice/employees
                    [:br-employee/employee-id
                     :br-employee/account-id,
                     :br-employee/total-usage-inc-vat]}]}
     (assert invoice-id)
     {:invoice/employees
      (->> (dev/caching #'queries/get-invoice-employees env invoice-id sid-part)
           (utils/sort-by-match sid-part :br-employee/employee-id))}))


(def attributes [invoice-id employee-id account-id total-usage])
