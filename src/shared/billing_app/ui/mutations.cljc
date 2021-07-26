;; NOTE: .cljC b/c the .ui that includes it is also .cljc
(ns billing-app.ui.mutations
  (:require
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.mutations :refer [defmutation]]
    [com.fulcrologic.rad.routing :as routing]))

(defmutation reset-global-error [_]
  (action [{:keys [state]}]
          (swap! state dissoc :ui/global-error))
  (refresh [_]
           [:ui/global-error]))

(defmutation set-selected-org [{:keys [orgnr]}]
  (action [{:keys [app state]}]
          (let [ident             [:organization/organization-number orgnr]
                OrgList           (or (comp/registry-key->class :billing-app.ui.billing.ui/OrgList)
                                      (throw (ex-info "set-selected-org: Could not find the component class for OrgList, was it renamed / moved?" {})))

                LatestInvoiceList (or (comp/registry-key->class :billing-app.ui.billing.ui/LatestInvoiceList)
                                      (throw (ex-info "set-selected-org: Could not find the component class for LatestInvoiceList, was it renamed / moved?" {})))]
            (swap! state assoc :ui/selected-org (when orgnr ident))
            (if orgnr
              ;; NOTE: We cannot route to Org.Dashboard, we must route to a _leaf_ target underneath it
              (routing/route-to! app LatestInvoiceList {:organization/organization-number orgnr})
              ;; else route back to the list
              (routing/route-to! app OrgList {})))))
