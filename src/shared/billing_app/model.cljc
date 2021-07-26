(ns billing-app.model
  "Collect all the attributes and resolvers"
  (:require
    [billing-app.model.billing :as billing]
    [billing-app.model.billing.invoice :as invoice]
    [billing-app.model.billing.invoice-error :as invoice-error]
    [billing-app.model.billing.invoice.employee :as br.employee]
    [billing-app.model.billing.invoice.charge :as br.charge]
    [billing-app.model.billing.phone-leasing :as phone-leasing]
    [com.fulcrologic.rad.attributes :as attr]))

(def all-attributes (vec (concat
                           billing/attributes
                           invoice/attributes
                           invoice-error/attributes
                           br.charge/attributes
                           br.employee/attributes
                           phone-leasing/attributes)))

(def all-attribute-validator (attr/make-attribute-validator all-attributes))

#?(:clj
   (def all-resolvers (vec (concat
                             [billing/all-organizations-search
                              br.employee/invoice-employees
                              br.employee/invoice-employees-parametrized
                              br.charge/invoice-employee-charges]
                             billing/resolvers
                             invoice/resolvers
                             invoice-error/resolvers
                             phone-leasing/resolvers))))
