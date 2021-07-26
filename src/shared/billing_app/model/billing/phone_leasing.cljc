(ns billing-app.model.billing.phone-leasing
  (:require
   #?@(:clj
       [[com.wsscode.pathom.connect :as pc :refer [defmutation]]
        [billing-app.components.fake-domain-client :as fake-domain-client]
        [billing-app.model.cache :as cache]]
       :cljs
       [[com.fulcrologic.fulcro.mutations :as m :refer [defmutation]]])
   [com.wsscode.pathom.connect :as pc]
   [com.fulcrologic.rad.attributes :as attr :refer [defattr]]
   [medley.core :refer [map-keys]]
   [billing-app.formatters.date-time :as fmt.date-time]))

;; Fee including VAT
(defattr period-fee :phone-leasing/period-fee :decimal {})
(defattr period-type :phone-leasing/period-type :string {})

(defattr period :phone-leasing/period :string {})

#?(:clj
   (pc/defresolver leased-devices [env {[orgnr sid :as ident] :orgnr+sid}]
     {::pc/input  #{:orgnr+sid}
      ::pc/output [:orgnr+sid
                   {:phone-leasing/leased-devices
                    [:phone-leasing/start-date
                     :phone-leasing/end-date
                     :phone-leasing/period
                     :phone-leasing/period-fee
                     :phone-leasing/period-type]}]}
     {:orgnr+sid ident
      :phone-leasing/leased-devices
                 (->> (cache/caching #'fake-domain-client/leasing-devices
                                     (get-in env [:com.fulcrologic.rad.database-adapters.sql/connection-pools :billing])
                                     orgnr)
                      (filter (comp #{sid} :sid))
                      (map #(-> %
                                (assoc :period (fmt.date-time/format-period (:start-date %) (:end-date %)))
                                (assoc :period-fee-exc-vat (:period %))
                                (update :period-fee (fn add-vat [fee] (* fee 1.25M)))))
                      (map (partial map-keys #(keyword "phone-leasing" (name %)))))}))

(def attributes [period-fee period-type period])

#?(:clj (def resolvers [leased-devices]))
