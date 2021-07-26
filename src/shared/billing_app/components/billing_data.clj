(ns billing-app.components.billing-data
  (:require
    [billing-app.model.cache :as dev]
    [billing-app.components.fake-domain-client :as fake-domain-client]
    [next.jdbc :as jdbc]
    [next.jdbc.result-set :as jdbc.rs]))

(defn ^:static accept-all [_] true)

(defn invoice-batches-raw-categorized
  "Raw batches based on invoices, i.e. without phone leasing"
  [ds invoice-id]
  (let [feature-mappings {}
        batches (fake-domain-client/find-subscr-batches ds invoice-id accept-all)]
    ;; NOTE: We cannot cache this to disk because it contains `meta` that we need
    ;; but EDN does not preserve them
    (with-meta
      (map (fn [batch] (map #(assoc % ::included?
                                      (-> % :kd/charge-type #{:ignored} not))
                            batch))
           batches)
      (meta batches))))

(defn leasing-charges
  "Get leasing charges from our DB as sid -> charge-like data, to merge with standard charges"
  [ds invoice-id {:keys [billing-period]}]
  {:pre [invoice-id billing-period]}
  (into {}
        (map (fn [{:keys [EMPLOYEE_ID, USAGE_INC_VAT]}]
               ;; See what billing-app.model.billing.invoice.charge/charge-view needs
               [EMPLOYEE_ID
                {:kd/usage-inc-vat USAGE_INC_VAT,
                 ;:kd/charge-type nil
                 :period (zipmap [:cbm/startDate :cbm/endDate] billing-period)
                 :debug {:name "Phone Leasing"}
                 ::included? true}]))
        (jdbc/plan
          ds
          ["select EMPLOYEE_ID, USAGE_INC_VAT
           from INVOICE_USAGE
           where CATEGORY='LEASING_COST' and INVOICE_ID=?"
           invoice-id]
          {:builder-fn jdbc.rs/as-unqualified-maps})))

(defn add-leasing-charge
  "Add a leasing charge to the list of a subscription charges, if available"
  [leasing-charges [{:kd/keys [sid]} :as subscr-charges]]
  (if-let [leasing-charge (get leasing-charges sid)]
    (conj subscr-charges leasing-charge)
    subscr-charges))

(defn billing-period+invoice-batches-with-leasing-cacheable
  {:ttl-ms (* 24 60 60 1000)}
  [ds invoice-id]
  (let [batches+meta (invoice-batches-raw-categorized ds invoice-id)
        {:keys [billing-period]} (meta batches+meta)
        leasing-charges (leasing-charges ds invoice-id {:billing-period billing-period})]
    ;; NOTE: Metadata cannot be cached so we need to return everything interesting as raw data
    [billing-period (map (partial add-leasing-charge leasing-charges) batches+meta)]))

(defn employee-batches [ds invoice-id employee-id]
  (let [[_billing-period batches] (dev/caching #'billing-period+invoice-batches-with-leasing-cacheable ds invoice-id)]
       (filter #(-> % first :kd/sid #{employee-id}) batches)))

(defn find-invoice-parts-too-long
  [ds invoice-id]
  (->> (dev/caching #'fake-domain-client/find-invoice-parts-too-long ds invoice-id)
       (map (fn [{:keys [billing/partNumber kd/invoicing-period account/accid]}]
              {:invoice-part/synt-id (str accid ":" partNumber)
               :invoice-part/number  partNumber
               :invoice-part/period  invoicing-period
               :invoice-part/accid   accid}))))

(defn simulate-invoice-processing
  [ds orgnr]
  (fake-domain-client/simulate-invoice-processing
    {:skip-apply? true, :tmp-invoice-id? true}
    orgnr))

(comment

  (dev/evict-all-for #'find-invoice-parts-too-long)
  (dev/evict-all-for #'billing-period+invoice-batches-with-leasing-cacheable)
  (dev/evict-all-for #'employee-batches)

  (employee-batches (development/get-jdbc-datasource) "inv201" "e10")

  (->> (jdbc/execute! (development/get-jdbc-datasource)
                      ["select employee_id from INVOICE_EMPLOYEE"])
       (map :INVOICE_EMPLOYEE/EMPLOYEE_ID)
       (sort)
       (dedupe))

  (->> (invoice-batches-raw-categorized (development/get-jdbc-datasource) "inv201")

       (filter #(-> % first :kd/sid #{"e10"}))))


