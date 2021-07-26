(ns billing-app.model.billing
  (:refer-clojure :exclude [name])
  (:require
    #?@(:clj
        [[com.wsscode.pathom.connect :as pc]
         [billing-app.components.database-queries :as queries]
         [billing-app.components.billing-data :as billing-data]
         [clojure.java.io :as io]
         [billing-app.model.cache :as cache]]
        :cljs
        [])
    [billing-app.utils :as utils]
    [billing-app.model.billing.invoice.employee :as br.employee]
    [clojure.string :as str]
    [com.wsscode.pathom.connect :as pc]
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.mutations :as m :refer [defmutation]]
    [com.fulcrologic.rad.form :as form]
    [com.fulcrologic.rad.attributes :as attr :refer [defattr]]
    [com.fulcrologic.rad.attributes-options :as ao]
    [clojure.set :as set]
    [com.fulcrologic.fulcro.data-fetch :as df]
    [com.fulcrologic.rad.report-options :as ro]))


;; For supported types, see com.fulcrologic.rad.database-adapters.sql.migration/type-map


;; ----------------------------------------------------------------- organization

(defattr organization-number :organization/organization-number :string
  {ao/identity?      true
   ao/schema        :billing
   ao/required?     true
   ::form/field-style   :autocomplete
   #_#_
   ::form/field-options {:autocomplete/search-key    :autocomplete/all-organizations-options
                         :autocomplete/debounce-ms   100
                         :autocomplete/minimum-input 3}})

(defattr org-timestamp :organization/invoice-ts :inst
  {ao/identities #{:organization/organization-number}
   ro/column-heading "Invoice since"
   ao/schema :billing})

(defattr current-bill-cycle-length :organization/current-bill-cycle-length :string
  {ao/identities #{:organization/organization-number}
   ao/schema     :billing})

(defattr current-bill-cycle-length-months :organization/current-bill-cycle-length-months :int
  {ao/identities #{:organization/organization-number}
   ao/pc-input #{:organization/current-bill-cycle-length}
   ao/pc-output [:organization/current-bill-cycle-length-months]
   ao/pc-resolve (fn [_ {len-word :organization/current-bill-cycle-length}]
                   #?(:clj {:organization/current-bill-cycle-length-months
                            (case len-word
                              "MONTH"   1
                              "QUARTER" 3
                              "UNKNOWN" 0)}))})

(defattr validation-errors :organization/validation-errors :string
  {ao/identities #{:organization/organization-number}
   ao/schema     :billing})

;; -------------------------------------------------------

#?(:clj
   (defn latest-organizations [env orgnr-part-search]
     (let [full-orgnr? (= 9 (count orgnr-part-search))
           matches (->> (cache/caching #'queries/get-latest-organizations env nil)
                        (utils/sort-by-match orgnr-part-search :organization/organization-number)
                        (take 50))
           match?  (some-> matches first :organization/organization-number
                           (str/includes? (or orgnr-part-search "")))]
       (cond
         (and full-orgnr? (not match?))
         (cache/caching #'queries/get-organization env orgnr-part-search)

         (not match?)
         []

         :default
         matches))))

(defattr all-organizations :billing/all-organizations :ref
  {ao/target     :organization/organization-number
   ao/pc-output  [{:billing/all-organizations [:organization/organization-number
                                               :organization/invoice-ts
                                               :organization/current-bill-cycle-length
                                               :organization/validation-errors
                                               :invoice-error/latest-error]}]
   ao/pc-resolve (fn [{:keys [query-params] :as env} _]
                   ;; (:route-params query-params)
                   #?(:clj
                      {:billing/all-organizations
                       (->> (latest-organizations env (some-> (:organization/organization-number query-params) str str/trim))
                            (map #(set/rename-keys % {:organization/latest-error :invoice-error/latest-error})))}))})

;(defattr organization-number-search :billing/organization-number-search :string
;  {ao/identity?         true
;   ::attr/schema            :billing
;   ;; Enumerations with lots of values should use autocomplete instead of pushing all possible values to UI
;   ::form/field-style       :autocomplete
;   ::form/field-options     {:autocomplete/search-key    :autocomplete/all-organizations-options
;                             :autocomplete/debounce-ms   100
;                             :autocomplete/minimum-input 3}})

#?(:clj
   (pc/defresolver all-organizations-search ;; NOTE: Works, but currently unused
     "A list of al organizations for autocomplete (with text = name, value = orgnr)"
     [{:keys [query-params] :as env} _]
     {::pc/output [{:autocomplete/all-organizations-options [:text :value]}]}
     (let [{:keys [only search-string]} query-params]
       {:autocomplete/all-organizations-options
        (cond
          (keyword? only)                                   ;; TODO What does `:only` mean, how to respond?
          [{:text (str/replace (clojure.core/name only) "_" " ") :value only}]

          (seq search-string)
          (let [search-string (str/lower-case search-string)]
            (into []
                  (comp
                    (map (fn [[name nr]] {:text (str name " - " nr) :value nr}))
                    (take 10))
                  (queries/get-all-organizations-name-and-nr env search-string)))
          :else
          [])})))

(def attributes [organization-number org-timestamp current-bill-cycle-length current-bill-cycle-length-months
                 validation-errors
                 all-organizations])

#?(:cljs
    (defmutation simulate-invoice-processing [{:keys [orgnr]}]
      (action [{:keys [app state ref]}]
              (df/set-load-marker! app :simulate-invoice-processing :loading))
      (remote [env] (m/returning env (doto (comp/registry-key->class :billing-app.ui.billing.ui/SimulateInvoiceProcessing) (assert))))
      (refresh [_] [:organization/organization-number orgnr])
      (ok-action [{:keys [app state]}] (df/remove-load-marker! app :simulate-invoice-processing))
      (error-action [{:keys [app state]}](df/set-load-marker! app :simulate-invoice-processing :failed)))
   :clj
    (pc/defmutation simulate-invoice-processing [env {:keys [orgnr]}]
      {;::pc/params [:orgnr]
       ::pc/output [:organization/organization-number :invoice/logs]}
      (assert orgnr "orgnr is required")
      (let [logs
            (:logs (billing-data/simulate-invoice-processing
                     (doto (get-in env [:com.fulcrologic.rad.database-adapters.sql/connection-pools :billing]) (assert "Missing billing DB"))
                     orgnr))]
        {:organization/organization-number orgnr
         :invoice/logs (vec logs)})))

#?(:clj
   (def resolvers [all-organizations-search
                   br.employee/invoice-employees
                   simulate-invoice-processing]))

(comment

  (latest-organizations
    (development/get-jdbc-datasource-in-env)
    "912345678")

  ((::pc/resolve all-organizations)
   (assoc (development/get-jdbc-datasource-in-env)
     :query-params
     {:organization/organization-number "912345678"})
   nil)

  nil)
