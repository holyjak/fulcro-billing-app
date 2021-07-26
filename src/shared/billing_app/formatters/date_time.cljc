(ns billing-app.formatters.date-time
  (:refer-clojure :exclude [name])
  (:require
    [com.fulcrologic.rad.type-support.date-time :as datetime])
  #?(:clj  (:import
             (java.time LocalDateTime ZoneId Instant)
             (java.time.format DateTimeFormatter)
             (java.util Date))
     :cljs (:import
             goog.i18n.DateTimeFormat)))

#?(:clj (def ^:private ^:static ^ZoneId UTC (ZoneId/of "UTC")))

(defn date-year [date]
  #?(:clj  (.getYear ^LocalDateTime date)
     :cljs (do
             (assert (instance? js/Date date) (str "`date` " date ": " (type date) " isn't js/Date"))
             (.getFullYear date))))

(defn current-year []
  (date-year
    #?(:clj  (LocalDateTime/now UTC)
       :cljs (js/Date.))))

(def fmt #?(:clj (-> (DateTimeFormatter/ofPattern "MMM d")
                     (.withZone UTC))
            :cljs (DateTimeFormat. "MMM d")))

#?(:clj (defn ->local-date ^LocalDateTime [date]
          (condp instance? date
            Date (->local-date (.toInstant date))
            Instant (LocalDateTime/ofInstant date UTC)
            LocalDateTime date)))

#?(:clj (defn ->instant ^Instant [date]
          (condp instance? date
            Date (.toInstant date)
            Instant date)))

(defn format-date [instant]
  (if instant
    (let [date #?(:clj (->local-date instant)
                  :cljs instant)
          year  (date-year date)]
      (cond-> (.format fmt date)
              (not= year (current-year)) (str ", " year)))
    "N/A"))

(defn format-period [from to]
  (str (format-date from) " - " (format-date to)))

(defn date-formatter
  "RAD report formatter that can be set on a RAD attribute (`::report/field-formatter`)"
  [report-instance value]
  (format-date value))

(comment
  (format-date #inst "2019-08-31T22:00:00.000-00:00") ; => "Aug 31, 2019"
  nil)
