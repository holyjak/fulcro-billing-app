(ns billing-app.components.fake-domain-client
  "Fake domain APIs client which, in the real app, made calls to domain REST endpoints
  to fetch data that does not reside in the app DB"
  (:require
    [next.jdbc.sql :as sql]
    [billing-app.utils :refer [inst->local-date]])
    ;[billing-app.components.config :as config]
    ;[billing-app.model.cache :as cache]
    ;[clj-http.client :as http]
    ;[clojure.data.xml :as xml]
    ;[clojure.string :as str]
  (:import (java.time Instant ZoneId)
           (java.util UUID)))

(defn fake-rand-charge [{:keys [empl-id invoice-id period max]}]
  {:kd/sid           empl-id
   :kd/usage-inc-vat (bigdec (rand-int max))
   :serviceType      (rand-nth ["AW" "ST" "GT" "HL"])
   :chargeType       (rand-nth ["R" "S" "O" "X"])
   :kd/charge-type   (rand-nth [:service :usage :one-time :other])
   :period           (zipmap [:billing/startDate :billing/endDate] period)
   :debug            {:invoice invoice-id :name "Service XYZ" #_"Phone Leasing"}})

(defn fake-rand-batch [{:keys [max] :as inputs}]
  (let [charges (repeatedly 100 (partial fake-rand-charge inputs))
        sums    (reductions + (map :kd/usage-inc-vat charges))
        sums-below-max (take-while (partial > max) sums)
        charges-below-max (take (count sums-below-max) charges)
        rem-charge (- max (last sums-below-max))]
    (-> charges-below-max
        (conj (assoc (fake-rand-charge inputs) :kd/usage-inc-vat rem-charge))
        (into (->> (repeatedly 3 (partial fake-rand-charge inputs))
                   (map #(assoc % :kd/charge-type :ignored))))
        (shuffle))))

(defn find-subscr-batches [ds invoice-id accept-all]
  (let [period
        (-> (sql/get-by-id ds :invoice invoice-id)
            (doto (assert "Invoice with the ID not found!"))
            ((juxt :INVOICE/INVOICE_PERIOD_FROM :INVOICE/INVOICE_PERIOD_TO)))

        empl+usgs
        (->> (sql/query ds ["select * from INVOICE_EMPLOYEE where INVOICE_ID=?" invoice-id])
             (map (juxt :INVOICE_EMPLOYEE/EMPLOYEE_ID :INVOICE_EMPLOYEE/TOTAL_USAGE_INC_VAT)))]
    (with-meta
      (map (fn [[empl-id max]]
             (fake-rand-batch {:empl-id empl-id :invoice-id invoice-id :period period :max max}))
           empl+usgs)
      {:billing-period period})))

(comment

  (->> (sql/query (development/get-jdbc-datasource) ["select * from INVOICE_EMPLOYEE where INVOICE_ID=?" "inv101"])
       (map (juxt :INVOICE_EMPLOYEE/EMPLOYEE_ID :INVOICE_EMPLOYEE/TOTAL_USAGE_INC_VAT)))


  (->> (next.jdbc/execute! (development/get-jdbc-datasource) ["SELECT * FROM INFORMATION_SCHEMA.TABLES"])
       (filter #(= (:TABLES/TABLE_SCHEMA %) "PUBLIC"))
       (map :TABLES/TABLE_NAME)
       (sort))


  (set! *print-length* nil)
  (first *2)

 ,)

(defn find-invoice-parts-too-long [ds invoice-id]
  (let [[from to] (-> (sql/get-by-id ds :invoice invoice-id)
                      (doto (assert "Invoice with the ID not found!"))
                      ((juxt :INVOICE/INVOICE_PERIOD_FROM :INVOICE/INVOICE_PERIOD_TO)))]
    [{:billing/partNumber  "1"
      :kd/invoicing-period [(-> (inst->local-date from)
                                (.minusMonths 1)
                                (.atStartOfDay (ZoneId/systemDefault))
                                (.toInstant))
                            to]
      :account/accid       "account123"}]))

(defn simulate-invoice-processing [opts orgnr]
  {:logs [{:level :info :description {:id :no-new-data :msg "There is no new invoice to process" :ts (java.time.Instant/now)}}]})

(defn leasing-devices [ds orgnr])
