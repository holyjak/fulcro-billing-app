(ns development
  (:require
    [clojure.pprint :refer [pprint]]
    [clojure.repl :refer [doc source]]
    [clojure.tools.namespace.repl :as tools-ns :refer [disable-reload! refresh clear set-refresh-dirs]]
    [billing-app.components.ring-middleware]
    [billing-app.components.server]
    [billing-app.components.database-queries :as queries]
    [billing-app.utils :refer [inst->local-date]]
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.rad.database-adapters.sql :as rad.sql]
    [com.fulcrologic.rad.ids :refer [new-uuid]]
    [com.fulcrologic.rad.resolvers :as res]
    [mount.core :as mount]
    [taoensso.timbre :as log]
    [next.jdbc :as jdbc]
    [next.jdbc.result-set :as jdbc.rs]
    [next.jdbc.sql :as sql]
    [billing-app.components.connection-pools :as pools])
  (:import (com.zaxxer.hikari HikariDataSource)
           (java.time LocalDate ZoneId Instant)))

(set-refresh-dirs "src/main" "src/sql" "src/dev" "src/shared" "../fulcro-rad/src/main" "../fulcro-rad-sql/src/main")

(defn get-jdbc-datasource
  "Returns a clojure jdbc compatible data source config."
  []
  (let [ds ^HikariDataSource (:billing pools/connection-pools)]
    ds))

(defn get-jdbc-datasource-in-env
  "Returns a fake env with the JDBC datasource at the expected location"
  []
  {:com.fulcrologic.rad.database-adapters.sql/connection-pools
   pools/connection-pools})


(defn add-namespace [nspc k] (keyword nspc (name k)))

(defn new-invoice
  ([org id shift-past] (new-invoice org id shift-past nil))
  ([{:keys [orgnr ^java.util.Date uploaded-ts cycle]}
    id
    shift-past
    {:keys [double-period?]}]
   (let [uploaded-date
         (-> (inst->local-date uploaded-ts)
             (.minusMonths (* cycle shift-past)))

         created-date (.plusDays uploaded-date 1)
         billing-date (.minusDays uploaded-date 7)
         period-end   billing-date
         period-start (.minusMonths period-end (* cycle (if double-period? 2 1)))]
     {:ID                  id
      :ORGANIZATION_NUMBER orgnr,
      :CREATED_DATE        created-date
      :billing_date        billing-date,
      :timestamp           uploaded-date,
      :invoice_period_from period-start,
      :invoice_period_to   period-end})))

(defn new-invoice-employee [org-idx empl-idx inv-id]
  {:INVOICE_ID          inv-id
   :EMPLOYEE_ID         (str "e" (inc org-idx) empl-idx),
   :ACCOUNT_ID          (rand-nth ["accounting" "IT" "sales" "maintenance" "HR"]),
   :TOTAL_USAGE_INC_VAT (+ (rand-nth [500M 213M 156M 97M 75M 50M 25M])
                           (rand-nth [0M 0.25M 0.5M 0.75M]))})

(comment
  (def db (get-jdbc-datasource))
  (:ORGANIZATION/INVOICE_TS (sql/get-by-id db :organization "123456789" :ORGANIZATION_NUMBER nil))
  ,)

(defn org [db orgnr]
  (let [{:keys [INVOICE_TS CURRENT_BILL_CYCLE_LENGTH] :as org}
        (sql/get-by-id db :organization orgnr :ORGANIZATION_NUMBER
                       {:builder-fn jdbc.rs/as-unqualified-maps})]
    (assert org)
    {:orgnr       orgnr
     :uploaded-ts INVOICE_TS
     :cycle       (if (= CURRENT_BILL_CYCLE_LENGTH "MONTH") 1 3)}))

(defn inv-id [org-idx inv-idx]
  (str "inv" (inc org-idx) 0 (inc inv-idx)))

(defn seed! []
  (let [db         (get-jdbc-datasource)
        exists?    (pos? (:CNT (jdbc/execute-one! db ["select count(1) as cnt from invoice"])))]
    (if exists?
      (log/info "Database already seeded. Skipping")
      (let [orgs [(org db "123456789")
                  (org db "234567890")
                  (org db "333333333")]]
        (log/info "Seeding development data")
        (doseq [[table entity :as row]
                (reduce
                  into []
                  [(for [org-idx (range 0 (count orgs))
                         inv-idx (range 0 3)]
                     [:INVOICE (new-invoice (orgs org-idx)
                                            (inv-id org-idx inv-idx)
                                            inv-idx
                                            ;; simulate an invoice with a period longer than should be
                                            {:double-period? (= org-idx inv-idx 0)})])

                   (for [org-idx  (range 0 (count orgs))
                         empl-idx (range 0 (inc (rand-int 100)))
                         inv-idx  (range 0 3)]
                     [:INVOICE_EMPLOYEE (new-invoice-employee
                                          org-idx empl-idx (inv-id org-idx inv-idx))])
                   ;; Each 0th employee on the latest invoice does lease a phone:
                   (for [org-idx  (range 0 (count orgs))]
                     [:INVOICE_USAGE {:INVOICE_ID (inv-id org-idx 0)
                                      :EMPLOYEE_ID (str "e" org-idx 0),
                                      :CATEGORY "LEASING_COST"
                                      :USAGE_INC_VAT 10M}])])]

          (try
            (sql/insert! db table entity)
            (catch Exception e
              (log/error e row))))))))

(defn start []
  (mount/start-with-args {:config "config/dev.edn"})
  (seed!)
  :ok)

(defn stop
  "Stop the server."
  []
  (mount/stop))

(def go start)

(defn restart
  "Stop, refresh, and restart the server."
  []
  (stop)
  (tools-ns/refresh :after 'development/start))

(def reset #'restart)

(comment
  (tools-ns/refresh)
  (go)
  (restart)
  (stop)

  (defn number-column-reader
    "An example column-reader that still uses `.getObject` but expands CLOB
    columns into strings."
    [^java.sql.ResultSet rs ^java.sql.ResultSetMetaData _ ^Integer i]
    (when-let [value (.getObject rs i)]
      (cond-> value
                (instance? BigDecimal value)
                (.longValue))))

  (let [db {:dbtype   "h2"
            :dbname   "billing"
            :user     "sa"
            :password (System/getenv "DB_PSW")}]
    (queries/get-invoice-employees-count
      {::rad.sql/connection-pools {:billing db}}
      "53518")
    #_(next.jdbc.sql/query db ["SELECT * from employees where id=53518"]
                           #_{:builder-fn (jdbc.rs/as-maps-adapter
                                            jdbc.rs/as-maps
                                            number-column-reader)}))

  (rad.sql/generate-resolvers account/attributes :production)

  ;(rad.sql/column-names account/attributes [::account/id ::account/active?])

  ;(contains? #{::account/name} (::attr/qualified-key account/name))
  nil)

(comment
  (sql/query (get-jdbc-datasource) ["select * from schema_version"])
  ,)
