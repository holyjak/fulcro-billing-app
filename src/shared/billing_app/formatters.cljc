(ns billing-app.formatters
  (:require
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.rad.report :as report]))

(defn formatted-column-value
  "Like com.fulcrologic.rad.report/formatted-column-value but ignoring the component options
  and modified to take either a component or an app.

  Orig docstring:

  Given a report instance, a row of props, and a column attribute for that report:
   returns the formatted value of that column using the field formatter(s) defined
   on the column attribute or report. If no formatter is provided a default formatter
   will be used."
  [app-or-component row-props {::report/keys      [field-formatter column-styles]
                               ::attr/keys [qualified-key type style] :as column-attribute}]
  (let [value                  (get row-props qualified-key)
        report-field-formatter nil ;(comp/component-options report-instance ::field-formatters qualified-key)
        {::app/keys [runtime-atom]} (comp/any->app app-or-component)
        formatter              (if report-field-formatter
                                 report-field-formatter
                                 (let [style                (or
                                                              (get column-styles qualified-key)
                                                              style
                                                              :default)
                                       installed-formatters (some-> runtime-atom deref ::report/type->style->formatter)
                                       formatter            (get-in installed-formatters [type style])]
                                   (or
                                     formatter
                                     (report/built-in-formatter type style)
                                     (fn [_ v] (str v)))))
        formatted-value        (formatter app-or-component value)]
    formatted-value))
