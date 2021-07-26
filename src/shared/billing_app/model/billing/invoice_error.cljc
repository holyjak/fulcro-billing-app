(ns billing-app.model.billing.invoice-error
  (:refer-clojure :exclude [name])
  (:require
    #?@(:clj
        [[com.wsscode.pathom.connect :as pc :refer [defmutation]]]
        :cljs
        [[com.fulcrologic.fulcro.mutations :as m :refer [defmutation]]])
    [com.wsscode.pathom.connect :as pc]
    [com.fulcrologic.rad.attributes :refer [defattr]]
    [com.fulcrologic.rad.attributes-options :as ao]))

(defattr organization-number :invoice-error/organization-number :string
         {ao/identity?      true
          ao/schema     :billing})

(defattr latest-error :invoice-error/latest-error :string
         {ao/identities #{:invoice-error/organization-number}
          ao/schema     :billing})

(defattr created :invoice-error/created :inst
         {ao/identities #{:invoice-error/organization-number}
          ao/schema     :billing})

#?(:clj
   (def connect-pk (pc/alias-resolver :organization/organization-number :invoice-error/organization-number)))

(def attributes [organization-number latest-error created])

#?(:clj (def resolvers [connect-pk]))
