(ns billing-app.components.database-queries
  (:require
    [com.fulcrologic.rad.database-adapters.sql :as sql]
    [com.fulcrologic.rad.database-adapters.sql.query :as query]
    [camel-snake-kebab.core :as csk]
    [next.jdbc.sql :as jdbc]
    [next.jdbc.result-set :as rs]
    [next.jdbc.date-time]                                   ;; convert times correctly
    [taoensso.timbre :as log]
    [clojure.string :as str]
    [billing-app.model.cache :as dev]))

;; --------------------------------------------------- JDBC config to use numbers instead of BigDecimals
;; (just to make it more readable in the Fulcro Inspect - Query, Fulcro handles BigDecimals just fine even in JS)

;(defn big-decimal->long-or-double [^BigDecimal x]
;  ;; See https://stackoverflow.com/questions/1078953/check-if-bigdecimal-is-integer-value
;  (if (pos? (.scale (.stripTrailingZeros x)))
;    (.doubleValue x)
;    (.longValue x)))

;;; Transit transits big decimals in a weird form, better to use double / long
;BigDecimal
;(read-column-by-label [^BigDecimal x _] (big-decimal->long-or-double x))
;(read-column-by-index [^BigDecimal x _2 _3] (big-decimal->long-or-double x)))

;; --------------------------------------------------- Queries & support

(defn mk-qualified-kebab-case-row-builder [ns]
  (fn qualified-kebab-case-row-builder [rs opts]
    (rs/as-modified-maps rs (assoc opts :qualifier-fn (constantly (name ns)) :label-fn csk/->kebab-case))))

(defn query->qualified-maps [ds q ns]
  (jdbc/query ds q {:builder-fn (mk-qualified-kebab-case-row-builder ns)}))

(defn get-latest-organizations
  "A list of orgs with a recent invoice, for the OrgList report"
  {:ttl-ms (* 5 60 1000)}
  [env query-params]
  (let [data-source    (get-in env [::sql/connection-pools :billing])
        ;; NOTE: In practice the SQL was more complicated - too complicated for RAD auto-generated resolvers
        sql
        "select o.ORGANIZATION_NUMBER, o.INVOICE_TS AS TIMESTAMP, o.CURRENT_BILL_CYCLE_LENGTH, o.VALIDATION_ERRORS, err.LATEST_ERROR
        from ORGANIZATION o
        left join invoice_error err ON o.ORGANIZATION_NUMBER = err.ORGANIZATION_NUMBER
        order by INVOICE_TS desc"]
    (when-not data-source (throw (ex-info "No DS in env!" {:billing-pool (:billing (::sql/connection-pools env))})))
    (query->qualified-maps data-source [sql] :organization)))

(defn get-organization
  {:ttl-ms (* 5 60 1000)}
  [env orgnr]
  (let [data-source    (get-in env [::sql/connection-pools :billing])
        sql
                       "select o.ORGANIZATION_NUMBER, INVOICE_TS AS TIMESTAMP, CURRENT_BILL_CYCLE_LENGTH, VALIDATION_ERRORS, err.LATEST_ERROR
        from ORGANIZATION
        left join invoice_error err ON o.ORGANIZATION_NUMBER = err.ORGANIZATION_NUMBER
        where ORGANIZATION_NUMBER=?"]
    (when-not data-source (throw (ex-info "No DS in env!" {:billing-pool (:billing (::sql/connection-pools env))})))
    (query->qualified-maps data-source [sql orgnr] :organization)))

(defn get-all-organizations-name-and-nr ;; for autocomplete (currently unused)
  "Return pairs [org-name org-number] for orgs containing the given search string in their name or number"
  [env search-string]
  (let [data-source    (get-in env [::sql/connection-pools :billing])
        search-col     (if (re-matches #"\d+" search-string) "org_no" "name")
        sql            (str "SELECT a.name, a.org_no
                         FROM agreement a JOIN organization o on o.organization_number=a.org_no
                         WHERE " search-col " like ?")
        rows           (rest
                         (jdbc/query data-source [sql (str/upper-case (str \% search-string \%))] {:builder-fn rs/as-arrays}))]
    rows))

(defn get-latest-invoices [env orgnr]
  (let [data-source    (get-in env [::sql/connection-pools :billing])
        query-params ["select inv.*, empl.nr_employees
                       from INVOICE inv
                       LEFT JOIN (select count(*) as nr_employees, INVOICE_ID from INVOICE_EMPLOYEE group by INVOICE_ID) empl
                       ON inv.id = empl.INVOICE_ID
                       WHERE ORGANIZATION_NUMBER=? AND DATEDIFF(MONTH, CURRENT_DATE, CREATED_DATE) <= 2
                       ORDER BY CREATED_DATE desc, ID desc"
                      orgnr]]
    (assert orgnr)
    (assert data-source)
    (query->qualified-maps data-source query-params :invoice)))

(defn get-invoice-employees
  ([env invoice-id] (get-invoice-employees env invoice-id nil))
  ([env invoice-id search]
   (let [data-source    (doto (get-in env [::sql/connection-pools :billing]) assert)
         searching?     (>= (count (str search)) 3)
         query-params (cond->
                        [(str "select EMPLOYEE_ID, ACCOUNT_ID, TOTAL_USAGE_INC_VAT
                               from INVOICE_EMPLOYEE
                               where INVOICE_ID = ? "
                              (when searching? " and EMPLOYEE_ID like ? ")
                              "order by TOTAL_USAGE_INC_VAT desc, EMPLOYEE_ID
                               limit 100")
                         invoice-id]
                        searching? (conj (str "%" search "%")))]

     (query->qualified-maps data-source query-params :br-employee))))

(comment
  (dev/evict-all-for #'get-invoice-employees)
  (get-invoice-employees (development/get-jdbc-datasource-in-env) "inv201")

  (set! *print-length* 100)
  (->> (next.jdbc/execute! (development/get-jdbc-datasource)
                      ["select employee_id from INVOICE_EMPLOYEE where invoice_id='inv201'"])
       (map :INVOICE_EMPLOYEE/EMPLOYEE_ID)
       (sort)
       (dedupe))


  (binding [*print-length* nil]
    (clojure.pprint/pprint
      (get-invoice-employees (development/get-jdbc-datasource-in-env) "inv201")))
  ,)
