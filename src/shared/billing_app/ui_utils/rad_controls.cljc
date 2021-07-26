(ns billing-app.ui-utils.rad-controls
  "Add to / override the default controls in
  `com.fulcrologic.rad.rendering.semantic-ui.semantic-ui-controls/all-controls`"
  (:require
    [clojure.string :as str]
    [com.fulcrologic.fulcro.algorithms.react-interop :as interop]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.mutations :as m]
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.rad.control :as control]
    [com.fulcrologic.rad.report :as report]
    [com.fulcrologic.rad.form :as form]
    [com.fulcrologic.rad.options-util :refer [?!]]
    [taoensso.timbre :as log]
    #?@(:cljs [[com.fulcrologic.fulcro.dom :as dom :refer [div]]
               ["semantic-ui-react" :refer [Popup]]]
        :clj  [[com.fulcrologic.fulcro.dom-server :as dom]])
    [com.fulcrologic.fulcro.dom.events :as evt]))

(def ui-popup #?(:cljs (interop/react-factory Popup)))

;; ------------------------------------------------------------------------ copied from com.fulcrologic.rad.rendering.semantic-ui.report:
(defn row-action-buttons
  "This is a copy of com.fulcrologic.rad.rendering.semantic-ui.report/row-action-buttons extended to support
  action button type of `:show-more`, which will display a pop-up with the provided `content-class`
  and either `(:ui/details-popup row-props)` if available or the `row-props` themselves otherwise."
  [report-instance row-props]
  (let [{::report/keys [row-actions row-pk]} (comp/component-options report-instance)
        row-pk-kwd  (::attr/qualified-key row-pk) ;; e.g. :invoice/id
        ;row-key-val (get row-props row-pk-kwd)   ;; e.g. 234566
        popup-data  (or (:ui/details-popup row-props) row-props)]
    (when (seq row-actions)
      (dom/div :.ui.buttons
           (map-indexed
             (fn [idx {:keys [label reload? visible? disabled? action type content-class]}]
               (when (or (nil? visible?) (?! visible? report-instance row-props))
                 (dom/div {:key label, :style {:position "relative"}}
                   (let [button
                         (dom/button :.ui.button
                                     {:key      idx
                                      :disabled (boolean (?! disabled? report-instance row-props))
                                      :onClick  (fn [evt]
                                                  (evt/stop-propagation! evt)
                                                  (when action
                                                    (action report-instance row-props)
                                                    (when reload?
                                                      (control/run! report-instance))))}
                                     (?! label report-instance row-props))]
                     (if (= type :show-more) ;; BEWARE: The report must use the `::report/row-style :show-more-table` for this to work
                       (ui-popup
                         {:trigger button
                          :on "click"
                          ; :onClose/:onOpen (fn [evt props])
                          :position "top right"
                          :wide "very"}
                         ((comp/factory content-class) popup-data))
                       button)))))
             row-actions)))))

(comp/defsc TableRowWithShowMoreLayout [_ {:keys [report-instance props] :as rp}]
  {}
  (let [{::report/keys [columns link links]} (comp/component-options report-instance)
        links          (or links link)
        action-buttons (row-action-buttons report-instance props)
        {:keys         [highlighted?]
         ::report/keys [idx]} (comp/get-computed props)]
    (dom/tr {:classes [(when highlighted? "active")]
             :onClick (fn [evt]
                        (evt/stop-propagation! evt)
                        (report/select-row! report-instance idx))}
            (map
              (fn [{::attr/keys [qualified-key] :as column}]
                (let [column-classes (report/column-classes report-instance column)]
                  (dom/td {:key     (str "col-" qualified-key)
                           :classes [column-classes]}
                          (let [{:keys [edit-form entity-id]} (report/form-link report-instance props qualified-key)
                                link-fn (get links qualified-key)
                                label   (report/formatted-column-value report-instance props column)]
                            (cond
                              edit-form (dom/a {:onClick (fn [evt]
                                                           (evt/stop-propagation! evt)
                                                           (form/edit! report-instance edit-form entity-id))} label)
                              (fn? link-fn) (dom/a {:onClick (fn [evt]
                                                               (evt/stop-propagation! evt)
                                                               (link-fn report-instance props))} label)
                              :else label)))))
              columns)
            (when action-buttons
              (dom/td :.collapsing {:key "actions"}
                      action-buttons)))))

(let [ui-table-row-layout (comp/factory TableRowWithShowMoreLayout)]
  (defn render-table-row-with-show-more [report-instance row-class row-props]
    (ui-table-row-layout {:report-instance report-instance
                          :row-class       row-class
                          :props           row-props})))

;; ------------------------------------------------------------------------ /END copied from com.fulcrologic.rad.rendering.semantic-ui.report

(def all-controls
  "Additional/override controls for `com.fulcrologic.rad.rendering.semantic-ui.semantic-ui-controls/all-controls`"
  ;; NOTE Currently (4/2020) not possible to globally define a fromatter for
  ;; a column value - see `com.fulcrologic.rad.report/formatted-column-value`
  {:com.fulcrologic.rad.report/row-style->row-layout
   {:show-more-table render-table-row-with-show-more}

   :com.fulcrologic.rad.control/type->style->control
   {:hidden {:default (constantly nil)}}})

