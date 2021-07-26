(ns billing-app.ui
  (:refer-clojure :exclude [Empty])
  (:require
    [billing-app.ui.billing.ui :as b.ui]
    [billing-app.ui.mutations :as mutations]
    [com.fulcrologic.fulcro.dom.html-entities :as ent]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    #?@(:clj  [[com.fulcrologic.fulcro.dom-server :as dom :refer [div label input]]]
        :cljs [[com.fulcrologic.fulcro.dom :as dom :refer [div label input]]
               ["semantic-ui-react" :refer [Message]]])
    [com.fulcrologic.fulcro.routing.dynamic-routing :refer [defrouter]]
    [com.fulcrologic.rad.ids :refer [new-uuid]]
    [com.fulcrologic.fulcro.algorithms.react-interop :as interop]))

(def ui-message #?(:cljs (interop/react-factory Message)))

(defsc Intro
  [_ _]
  {:query ['*]
   :initial-state {}
   :ident (fn [] [:component/id :Intro])
   :route-segment [""]}
  (dom/p "Choose one of the tabs at the top"))

(defrouter RootRouter [_ {:keys [current-state]}]
  {:router-targets [Intro b.ui/Billing]}
  (condp contains? current-state
    #{nil :pending} (dom/p :.ui.active.loader "")
    #{:failed} (dom/p "Subroute failed :-(")
    nil))

(def ui-root-router (comp/factory RootRouter))

(defsc GlobalErrorDisplay [this {:ui/keys [global-error] :as props}]
  {:query [[:ui/global-error '_]]
   :ident (fn [] [:component/id :GlobalErrorDisplay])
   :initial-state {}}
  (when global-error
    (ui-message
      {:content (str "Something went wrong: " global-error)
       :error   true
       :onDismiss #(comp/transact!! this [(mutations/reset-global-error)])})))


(def ui-global-error-display (comp/factory GlobalErrorDisplay))

(defsc Root [_ {::app/keys  [active-remotes]
                :root/keys [root-router global-error]
                :ui/keys   [ready?]}]
  {:query         [::app/active-remotes
                   {:root/root-router (comp/get-query RootRouter)}
                   {:root/global-error (comp/get-query GlobalErrorDisplay)}
                   :ui/ready?]

   :initial-state {:root/root-router {} :root/global-error {}}} ; :details-router {}, :org-list {}
  (dom/div
    (div :.ui.top.menu
         (div :.ui.item "Billing Troubleshooting App")
         (div :.ui.item (dom/a {:href "/billing/velg-org"} "billing"))
         (div :.ui.tiny.loader {:classes [(when (or (seq active-remotes) (not ready?)) "active")]}))
    (when ready?
      (div :.ui.container.segment
           (ui-global-error-display global-error)
           (ui-root-router root-router)))))
