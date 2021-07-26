(ns billing-app.ui.billing.ui
  "The main part of the UI"
  (:refer-clojure :exclude [Empty])
  (:require
    [clojure.string :as str]
    [billing-app.formatters :as formatters]
    [billing-app.ui.mutations :as mutations]
    [billing-app.model.billing.invoice :as invoice]
    [billing-app.model.billing.invoice-error :as invoice-error]
    [billing-app.model.billing.invoice.employee :as br.employee]
    [billing-app.model.billing.invoice.charge :as br.charge]
    [billing-app.model.billing :as billing]
    [com.fulcrologic.rad.type-support.decimal :as math]
    [com.fulcrologic.fulcro.algorithms.react-interop :as interop]
    [com.fulcrologic.fulcro.data-fetch :as df]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    #?@(:clj  [[com.fulcrologic.fulcro.dom-server :as dom :refer [div label input]]]
        :cljs [[com.fulcrologic.fulcro.dom :as dom :refer [div label input]]
               ["semantic-ui-react" :as sui :refer [Accordion]]])
    [com.fulcrologic.fulcro.routing.dynamic-routing :as dr :refer [defrouter]]
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.rad.control :as control]
    [com.fulcrologic.rad.report-options :as ro]
    [com.fulcrologic.rad.report :as report]
    [com.fulcrologic.rad.routing :as routing]
    [taoensso.timbre :as log]))

(def ui-accordion (interop/react-factory #?(:cljs Accordion :clj nil)))

(defsc Empty
  "An empty component, usable as the default target of a router"
  [_ _]
  {:query ['*]
   :initial-state {}
   :ident (fn [] [:component/id :Empty])
   :route-segment [""]}
  (dom/p ""))

(declare OrgDashboard)

(report/defsc-report OrgList [this {:ui/keys [current-rows] :as props}]
                     {ro/title                    "Organizations with an invoice in the past 31 days"
                      ;ro/layout-style             :table
                      ;ro/row-style                :table
                      ;ro/field-formatters         {:account/name (fn [v] v)}
                      ;ro/column-headings          {:account/name "Account Name"}
                      ro/columns                  [billing/organization-number
                                                   billing/org-timestamp
                                                   billing/validation-errors
                                                   invoice-error/latest-error]
                      ro/row-pk                   billing/organization-number
                      ro/field-formatters         {:invoice-error/latest-error (fn [_ value] (when value "Ja"))}
                      ro/column-headings          {:invoice-error/latest-error "Feilet?"}
                      ro/source-attribute         :billing/all-organizations
                      ro/run-on-mount?            true
                      ro/controls                 {::refresh        {:type   :button
                                                                     :label  "Refresh"
                                                                     :action (fn [this] (control/run! this))}
                                                   :organization/organization-number
                                                   {:type :string
                                                    :style :search
                                                    :default-value ""
                                                    :label "Org.nr."
                                                    :onChange (fn [this _] (control/run! this))}}
                      ro/control-layout           {:action-buttons [::refresh]
                                                   :inputs [[:organization/organization-number]]}
                      ro/row-actions              [{:label     "Select"
                                                    :action    (fn [report-instance {:organization/keys [organization-number]}]
                                                                 (comp/transact!
                                                                   report-instance
                                                                   [(mutations/set-selected-org {:orgnr organization-number})]))}]
                      ro/route                    "velg-org"}
  (dom/div
    (when (and
            (empty? current-rows)
            (not (str/blank? (control/current-value this :organization/organization-number))))
      (dom/p :.ui.attached.info.message
             (str "No org with a *recent* invoice matching '"
                  (control/current-value this :organization/organization-number)
                  "'. TIP: Search for the full organization number to find it no matter when its last invoice was (as long as there was any).")))
    (report/render-layout this)))

(def ui-org-list (comp/factory OrgList))

(defn ui-format
  "Format a value for display in UI"
  ([v] (ui-format v nil))
  ([v type]
   (cond
     #?(:cljs (instance? js/Date v) :clj false)
     (subs (pr-str v) 7 17) ;; "#inst \"2020-03-27T13:..." -> "2020-03-27"

     (and (= type :bool) (number? v))
     (pos? v)

     :else (str v))))

(declare ChargeList LatestInvoiceList)

(report/defsc-report EmployeeList [this props]
  {ro/title            "Employees with charge data (the first 100 with the greatest amount)"
   ro/columns          [br.employee/employee-id
                        br.employee/account-id
                        br.employee/total-usage]
   ro/row-pk           br.employee/employee-id
   ro/source-attribute :invoice/employees
   ro/run-on-mount?    true
   ro/controls         {::hide {:type   :button
                                :label  "Invoices"
                                :action (fn [report-instance _]
                                          (routing/route-to! report-instance LatestInvoiceList
                                                             (comp/get-computed report-instance)))}
                        :invoice/id
                               {:type :hidden #_:int :onChange (fn [this _] (control/run! this))}
                        :br-employee/employee-id
                               {:type          :string
                                :style         :search
                                :default-value ""
                                :onChange      (fn [this _] (control/run! this))
                                :label         "Tlf"}}
   ro/control-layout   {:action-buttons [::hide]
                        :inputs [[:br-employee/employee-id :invoice/id]]}
   ro/row-actions      [{:label  "Charges"
                         :action (fn [report-instance {:br-employee/keys [employee-id]}]
                                   (routing/route-to! report-instance ChargeList
                                                      (merge (comp/get-computed report-instance) ; <- orgnr
                                                             {:br-employee/employee-id employee-id
                                                              :invoice/id                 (control/current-value report-instance :invoice/id)})))}]
   ro/route            "employees"})

;;---------------------------------------------------------------------- entity-table

(defn column-headings [attrs]
  (map (fn [{:keys [::report/column-heading ::attr/qualified-key :mb/description]}]
         (let [label (or column-heading (name qualified-key))]
           (if description
             (dom/span {:title description} label (dom/sup (dom/i :.question.circle.icon)))
             label)))
       attrs))

(defn column-vals [app-or-component attrs entity]
  (for [col attrs]
    (formatters/formatted-column-value
      app-or-component entity col)))

(defn entity-table
  "Display a data entity (a map described by a set of attributes) in a table"
  [app-or-component attrs entity]
  (dom/table
    (dom/tbody
      (map
        (fn [attr label val]
          (let [key (-> attr ::attr/qualified-key name)]
            (dom/tr {:key key} (dom/td label) (dom/td val))))
        attrs
        (column-headings attrs)
        (column-vals app-or-component attrs entity)))))

;;---------------------------------------------------------------------- /entity-table

(defsc EmployeePhoneLeasingDetails [_ {devices :phone-leasing/leased-devices :as props}]
  {:query [:orgnr+sid
           [df/marker-table :phone-leasing-details]
           {:phone-leasing/leased-devices [:phone-leasing/period
                                           :phone-leasing/period-fee
                                           :phone-leasing/period-type]}]
   :ident :orgnr+sid}
  (let [marker (get props [df/marker-table :phone-leasing-details])]
    (dom/div {:style {:minWidth "18em"}}
      (if (df/loading? marker)
        (dom/p :.ui.active.loader "")
        (map-indexed
          (fn [idx {:phone-leasing/keys [period-fee period-type period]}]
            (dom/table {:key idx}
              (dom/tbody
                (dom/tr (dom/td "Fee") (dom/td (str (math/numeric->str period-fee) " / " (name period-type))))
                (dom/tr (dom/td "Period") (dom/td (str period))))))
          devices)))))

(defsc GenericReportRowPopupBody
  "Show row props in a table. To be displayed by a `:show-more`-style report row action."
  [_ props]
  {}
  (let [props (dissoc props :fulcro.client.primitives/computed)]
    (dom/table
      (dom/tbody
        (map
          #(dom/tr {:key %1} (dom/td %1)
                   (dom/td (cond
                             (math/bigdecimal? %2) (math/numeric->str %2)
                             :else (str %2))))
          (->> props keys (map name))
          (->> props vals))))))

(report/defsc-report ChargeList [this props]
  {ro/title                    "Top charges of the employee (slow for the first employee!)"
   ro/columns                  [br.charge/synt-id
                                br.charge/usage
                                br.charge/service-name
                                br.charge/period-from
                                br.charge/period-to
                                br.charge/included?]
   ro/row-pk                   br.charge/synt-id
   ro/source-attribute         :invoice-employee-charges
   ro/run-on-mount?            true

   ::report/row-style          :show-more-table ; like :table but with a Show More... row action
   ro/row-query-inclusion      [:charge/employee-id
                                ;; Extra info for the Details popup:
                                :charge/charge-type
                                :charge/invoice
                                :charge/service-type
                                ;; Extra info for the leasing popup:
                                {:ui/details-popup (comp/get-query EmployeePhoneLeasingDetails)}
                                [df/marker-table :phone-leasing-details]]
   ro/row-actions              [{:type          :show-more ; show content of :ui/details-popup or all props inside a popup
                                 :label         "Leasing info"
                                 :visible?      (fn [_ {srv :charge/service-name}] (= srv "Phone Leasing"))
                                 :content-class EmployeePhoneLeasingDetails
                                 :action        (fn [report-instance {:charge/keys [synt-id employee-id], popup-data :ui/details-popup}]
                                                  (let [orgnr (:organization/organization-number (comp/get-computed report-instance))]
                                                    (when-not popup-data
                                                      (df/load! report-instance
                                                                [:orgnr+sid [orgnr employee-id]]
                                                                EmployeePhoneLeasingDetails
                                                                {:marker :phone-leasing-details
                                                                 :target [:charge/synt-id synt-id :ui/details-popup]}))))}
                                {:type :show-more ; show content of :ui/details-popup or all props inside a popup
                                 :label "Details"
                                 :visible? (fn [_ {srv :charge/service-name}] (not= srv "Phone Leasing"))
                                 :content-class GenericReportRowPopupBody}]
   ro/controls                 {::hide {:type :button
                                        :label  "Invoices"
                                        :action (fn [report-instance _]
                                                  (routing/route-to! report-instance LatestInvoiceList
                                                                     (comp/get-computed report-instance)))}
                                ;; Hidden report params, supplied via route params:
                                :invoice/id {:type :hidden #_:int}
                                :br-employee/employee-id {:type :hidden #_:string}
                                :organization/organization-number {:type :hidden #_:string}}
   ro/control-layout           {:action-buttons [::hide]
                                :inputs [[:invoice/id :br-employee/employee-id :organization/organization-number]]}
   ro/route                    "charges"}
  (dom/div
    (dom/p (dom/i :.info.circle.icon) "The list below includes all incoming charges, only those marked as included are actually included in the resulting usage.")
    (report/render-layout this)
    (dom/p (dom/em
             (str "NOTE: Phone leasing charges are estimated based on the *current* data and might differ from the charged ones.")))))

(report/defsc-report LatestInvoiceList [this props]
  {ro/title   "The latest invoices"
   ro/columns [invoice/invoice-id
               invoice/billing-date
               invoice/created-date
               invoice/timestamp
               invoice/period
               invoice/nr-employees
               invoice/valid
               invoice/cache-done
               invoice/notification-sent]
   ro/row-pk   invoice/invoice-id
   ro/source-attribute :organization/latest-invoices
   ro/run-on-mount?            true
   #_#_:months-back {:type :int}
   ro/controls                 {#_#_
                                ::refresh        {:type   :button ;; TODO Add "force" option so that refresh actually makes sense
                                                  :label  "Refresh"
                                                  :action (fn [this] (control/run! this))}
                                :organization/organization-number {:type :hidden #_:string :onChange (fn [this _] (control/run! this))}}
   #_#_
   ro/control-layout           {:action-buttons [#_::refresh]
                                :inputs [:organization/organization-number]}
   ro/route   "invoices"})

(defrouter DetailsDisplayRouter [_ {:keys [current-state] :as props}]
  {:router-targets [LatestInvoiceList EmployeeList ChargeList Empty]}
  (case current-state
    :pending (dom/p "Loading...")
    :failed (dom/p "Failed loading details")
    (do ;; :initial / nil: no route selected yet
      (when props
        (log/warn "DetailsDisplayRouter: No route selected, did you route to a _leaf_ target or just a partial route? See 'Partial Routes' in the Fulcro Book"))
      nil)))

(def ui-details-display-router (comp/computed-factory DetailsDisplayRouter))

(def br-columns [invoice/invoice-id
                 invoice/billing-date
                 invoice/created-date
                 invoice/timestamp
                 invoice/period
                 invoice/nr-employees
                 invoice/valid
                 invoice/cache-done
                 invoice/notification-sent])

(defsc SimulateInvoiceProcessing
  [this
   {logs :invoice/logs
    orgnr :organization/organization-number
    err   :com.fulcrologic.fulcro.mutations/mutation-error
    :as props}]
  {:ident :organization/organization-number
   :query [:organization/organization-number
           :invoice/logs
           :com.fulcrologic.fulcro.mutations/mutation-error
           [df/marker-table :simulate-invoice]]}
  (let [marker (get props [df/marker-table :simulate-invoice])]
    (div :.ui.segment {:data-qa "SimulateInvoiceProcessing"}

         (cond
           logs
           (ui-accordion {:defaultActiveIndex 0
                          :panels             [{:key     "logs"
                                                :title   "Logs from the simulated invoice processing (latest first)"
                                                :content {:content (dom/div
                                                                     (->> logs
                                                                          (map-indexed
                                                                            (fn [idx {:keys [level description]}]
                                                                              (dom/span
                                                                                {:key   idx
                                                                                 :style {:color (when-not (= level :info) "red")}}
                                                                                (dom/i (some-> description :id name))
                                                                                ": "
                                                                                (or (:msg description)
                                                                                    (pr-str description))
                                                                                (when-let [data (seq (dissoc description :id :msg))]
                                                                                  (str " Details: " (pr-str (into {} data))))
                                                                                (dom/br))))))}}]})

           err ; this is the whole env with :result etc.
           (dom/p :.ui.message (str "Failed to simulate invoice processing: "
                                    (get-in err [:body `billing/simulate-invoice-processing :com.fulcrologic.rad.pathom/errors :message])))

           :else
           (dom/button :.ui.button
                       {:type     "button"
                        :title    "Simulate starting processing of the latest, new invoice and display the logs"
                        :disabled (df/loading? marker)
                        :classes  [(when (df/loading? marker) "loading")]
                        :onClick  #(comp/transact!
                                     this
                                     ;; Note: Ignore IntelliJ error about "incorrect arity" of the mutation, it mixes up clj x cljs
                                     [(billing/simulate-invoice-processing {:orgnr orgnr})])}
                       "Simulate a new invoice processing!"
                       (dom/sup (dom/i :.question.circle.icon)))))))

(def ui-simulate-invoice (comp/factory SimulateInvoiceProcessing))

(defn inv-period-too-long? [invoice-period-months bill-months]
  (and invoice-period-months bill-months
       (> invoice-period-months bill-months)))

(defsc InvoicePartDetails [_ {:invoice-part/keys [number accid period]}]
  {:ident :invoice-part/synt-id
   :query [:invoice-part/synt-id
           :invoice-part/number
           :invoice-part/period
           :invoice-part/accid]}
  (dom/li (str "Invoice part " number " for account " accid ": period "
               (str/join " - "(map ui-format period)))))

(def ui-invoice-part (comp/factory InvoicePartDetails {:keyfn :invoice-part/synt-id}))

(defsc Invoice
  "Show details of the latest invoice of an org."
  [this
   {:invoice/keys [id nr-employees invoice-period-months invoice-parts-too-long]
    :as invoice}
   {:organization/keys [organization-number current-bill-cycle-length-months]}]
  {:ident             :invoice/id
   :query             [:invoice/id
                       :invoice/billing-date
                       :invoice/period
                       :invoice/timestamp
                       :invoice/created-date :invoice/cache-done :invoice/valid
                       :invoice/nr-employees
                       :invoice/nr-invoices
                       :invoice/invoice-period-months
                       {:invoice/invoice-parts-too-long (comp/get-query InvoicePartDetails)}
                       [df/marker-table '_]]
   :initial-state     {:invoice/invoice-parts-too-long []}
   :pre-merge     (fn [{:keys [data-tree]}]
                    (update data-tree :invoice/invoice-parts-too-long (fnil identity [])))
   :componentDidMount (fn load-invoice-parts-too-long-when-any [this]
                        (let [{:invoice/keys [invoice-period-months invoice-parts-too-long]}
                              (comp/props this)

                              bill-months
                              (comp/get-computed this :organization/current-bill-cycle-length-months)]
                          (when (and (empty? invoice-parts-too-long) (inv-period-too-long? invoice-period-months bill-months))
                            (df/load-field! this :invoice/invoice-parts-too-long {:marker :invoice-parts-too-long}))))}
  (dom/div
    (let [attrs (->> (conj br-columns invoice/employee-notification-sent)
                     (remove #{invoice/nr-employees}))]
      (entity-table this attrs invoice))

    (dom/p
      (dom/a {:style {:cursor "pointer"}
              :title (str "Show the list of employees for org " organization-number " and invoice " id)
              :onClick
                     #(routing/route-to! this EmployeeList
                                         {:organization/organization-number organization-number
                                          :invoice/id                       id})}
             (dom/span {:title " Klikk her for å se de."}
                       (str "Employees (" (math/numeric->str (math/numeric (or nr-employees 0))) ")")
                       (dom/sup (dom/i :.question.circle.icon)))))

    (when (inv-period-too-long? invoice-period-months current-bill-cycle-length-months)
      (dom/section :.ui.segment
                   (dom/h4 "Troublesome invoice parts")
                   (dom/div :.ui.warning.message
                            (dom/p (str "The invoicing period ("
                                        invoice-period-months
                                        " months) is longer than the expected billing period ("
                                        current-bill-cycle-length-months
                                        " months)"))
                            (dom/p "This is likely due to carry-over or other issues. Explore the invoice parts listed below to learn more."))
                   (dom/div :.ui.segment
                            (dom/div :.ui.dimmer
                                     {:className (when (or (empty? invoice-parts-too-long) (df/loading? (get invoice [df/marker-table :invoice-parts-too-long]))) "active")}
                                     (dom/div :.ui.text.loader "Laster inn detaljer..."))
                            (dom/ul (map ui-invoice-part invoice-parts-too-long)))))))

(def ui-invoice (comp/computed-factory Invoice))

(defsc OrgDashboard [this {:organization/keys [organization-number latest-invoice current-bill-cycle-length-months validation-errors]
                           :keys [br/details-router com.wsscode.pathom.core/errors]
                           invoice-error :invoice-error/latest-error
                           simulate-invoice :>/simulate-invoice
                           :as props}]
  {:query         [[df/marker-table :ui/selected-org]
                   :organization/organization-number
                   :organization/current-bill-cycle-length-months
                   :organization/validation-errors
                   :invoice-error/latest-error
                   {:organization/latest-invoice (comp/get-query Invoice)}
                   {:>/simulate-invoice (comp/get-query SimulateInvoiceProcessing)}
                   {:br/details-router (comp/get-query DetailsDisplayRouter)}
                   :com.wsscode.pathom.core/errors]

   :ident         :organization/organization-number
   :route-segment ["org" :organization/organization-number]
   :will-enter    (fn [app {orgnr :organization/organization-number}]
                    (let [ident [:organization/organization-number orgnr]]
                      (if (-> (app/current-state app) (get-in ident) :organization/latest-invoice)
                        (dr/route-immediate ident)
                        (dr/route-deferred ident
                                           #(df/load! app ident OrgDashboard
                                                      {:without              #{:invoice/employees :invoice/invoice-parts-too-long}
                                                       :marker               :ui/selected-org
                                                       :target               [:ui/selected-org]
                                                       :post-mutation        `dr/target-ready
                                                       :post-mutation-params {:target ident}})))))
   :initial-state {:organization/latest-invoice {}, :br/details-router {}}
   :pre-merge     (fn [{:keys [data-tree state-map]}]
                    (assoc data-tree
                      :br/details-router (merge
                                           (comp/get-initial-state DetailsDisplayRouter)
                                           (get-in state-map (comp/get-ident DetailsDisplayRouter {})))))}

  (dom/div
    (dom/h2 (str "Org: " organization-number)
            " "
            (dom/a
              {:onClick #(comp/transact!
                           this
                           [(mutations/set-selected-org {:orgnr nil})])}
              (dom/i :.close.icon)))
    (when (seq errors)
      (dom/div :.ui.message
               (dom/p (str "Error fetching some data - " (clojure.string/join ", " (keys errors))))
               (dom/p "Something might (not) work.")))
    (when validation-errors
      (dom/div :.ui.message
               (dom/p (str "The org. has some validation issues: " validation-errors))))
    (let [marker (get props [df/marker-table :ui/selected-org])]
      (cond
        (df/loading? marker)
        (div :.ui.tiny.loader.active "Loading org data...")

        (df/failed? marker)
        (div :.ui.warning.message "Failed to load org data :-(")

        :else
        (dom/div
          (when invoice-error
            (dom/h3 "Failed invoice processing details")
            (dom/div :.ui.error.message
                   "The latest invoice processing has failed. Cause: "
                   (dom/sup (dom/i :.question.circle.icon))
                   (dom/blockquote invoice-error)))

          (dom/h3 "Latest invoice")
          (if (:invoice/id latest-invoice)
            (ui-invoice latest-invoice {:organization/organization-number organization-number
                                          :organization/current-bill-cycle-length-months current-bill-cycle-length-months})
            (dom/p "N/A - perhaps there were problems processing the invoice? You can check that by using the 'Simulate a new invoice processing!' underneath."))

          (ui-simulate-invoice simulate-invoice)

          (ui-details-display-router details-router
                                     {:organization/organization-number organization-number}))))))

(def ui-org-dashboard (comp/factory OrgDashboard))

(defrouter OrgRouter [this {:keys [current-state route-factory route-props]}]
  {:router-targets [OrgList OrgDashboard]
   :always-render-body? true}
  (when (nil? current-state)
    ;; Reports do not work as the default router target, they need to be
    ;; routed to explicitly - and `nil` means there is no explicit route yet.
    (log/warn "OrgRouter: No route selected, did you route to a _leaf_ target or just a partial route? See 'Partial Routes' in the Fulcro Book")
    nil)

  (case current-state
    :failed
    (div :.ui.error.message (dom/p "It is taking long time to load.... (something might have failed)"))

    :routed
    (route-factory (comp/computed route-props (comp/get-computed this)))

    (div :.ui.active.inverted.dimmer
         (div :.ui.text.loader "Laster inn..."))))

(def ui-org-router (comp/factory OrgRouter))

(def orglist-load-marker [df/marker-table [:component/id :OrgList]])

(defsc Billing
  [_ {:keys [root/org-router] :as props}]
  {:query         [::app/active-remotes
                   orglist-load-marker
                   {:root/org-router (comp/get-query OrgRouter)}
                   {:ui/selected-org (comp/get-query OrgDashboard)}]
   :initial-state {:root/org-router {}, :ui/selected-org {}}
   :ident         (fn [] [:component/id ::billing])
   :route-segment ["billing"]}
  (div :.ui.container.segment
       (dom/h1 "Billing Troubleshooting")
       (dom/p {:style {:fontSize "smaller"}} "Note: All monetary amounts are including VAT")
       (when (df/failed? (get props orglist-load-marker))
         (dom/p :.ui.warning.message "Failed to load organizations :-("))
       (ui-org-router org-router)))

(def ui-root (comp/factory Billing))

(comment

  (set! *print-level* 3)
  (set! *print-length* 20)


  (keys (get-in
          @(:com.fulcrologic.fulcro.application/runtime-atom billing-app.client/app)
          [:com.fulcrologic.rad/controls
           :com.fulcrologic.rad.report/parameter-type->style->input #_#_:boolean :default]))

  (df/load! billing-app.client/app
            :invoice-employee-charges
            ChargeList
            {:params {:br-employee/employee-id "123"
                      :invoice/id 456}})

  (let [state (app/current-state billing-app.client/app)]
    (com.fulcrologic.fulcro.algorithms.denormalize/db->tree
      (comp/get-query OrgDashboard)
      state
      state))
  (-> *1 :ui/selected-org)

  (app/force-root-render! billing-app.client/app)

  (-> billing-app.client/app
      :com.fulcrologic.fulcro.application/runtime-atom
      deref
      :com.fulcrologic.rad/controls
      :com.fulcrologic.rad.report/style->layout)

  (comp/get-query LatestInvoiceList)
  (cljs.pprint/pp)
  (control/run!
   (comp/class->any billing-app.client/app
                    OrgList))

  (routing/route-to! billing-app.client/app OrgList {})
  (routing/route-to! billing-app.client/app Empty
                     {:organization/organization-number "996231771"})
  (routing/route-to! billing-app.client/app LatestInvoiceList
                     {:organization/organization-number "996231771"
                      :dummy-param "new value"})
  nil)

