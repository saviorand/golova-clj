(ns golova.ui.popover
  "Floating popovers — anchored cards rendered at the root. Uses antd
  Popover and Dropdown for domain menus and move-to pickers."
  (:require [reagent.core :as r]
            [golova.state :as state :refer [app-state]]
            [golova.ui.antd :as antd]))

(defn open-popover-from-event! [m e]
  (let [t (.-currentTarget e)
        r (.getBoundingClientRect t)]
    (swap! app-state assoc :popover
           (assoc m :anchor {:left (.-left r) :top (.-bottom r)
                             :right (.-right r) :width (.-width r)}))))

(defn close-popover! [] (swap! app-state assoc :popover nil))

(defn popover-shell [anchor & body]
  [:div.popover {:style {:left (str (max 8 (- (:left anchor) 0)) "px")
                         :top  (str (+ 6 (:top anchor)) "px")}}
   (into [:<>] body)])

(defn move-to-pill [src-id mover]
  (let [label (some-> (state/domain-info src-id) :label)]
    (when label
      [:span.pill.movable
       {:title "Move to another domain"
        :on-click (fn [e] (open-popover-from-event!
                           {:kind :move-to :src src-id :mover mover} e))}
       "in " [:b label] " ▾"])))

(defn popover []
  (let [p (:popover @app-state)]
    (when p
      [:div.popover-overlay
       {:on-click (fn [e]
                    (when (= (.-target e) (.-currentTarget e))
                      (close-popover!)))}
       (case (:kind p)
         :domain-menu
         [popover-shell (:anchor p)
          [:div.popover-card
           [:div.popover-title
            [:b (some-> (state/domain-info (:domain p)) :label)]]
           [:button.popover-item
            {:on-click #(do (close-popover!)
                            (state/select! {:kind :domain :domain (:domain p)}))}
            [:span.k "□"] " Open domain page"]
           [:button.popover-item
            {:on-click #(do (close-popover!)
                            (state/open-modal! {:kind :new-domain
                                                :parent (:domain p)}))}
            [:span.k "+"] " Add subdomain…"]
           [:div.popover-sep]
           [:button.popover-item
            {:on-click #(do (close-popover!)
                            (state/open-modal! {:kind :new-type :domain (:domain p)}))}
            [:span.k "◆"] " Add type"]
           [:button.popover-item
            {:on-click #(do (close-popover!)
                            (state/open-modal! {:kind :new-predicate :domain (:domain p)}))}
            [:span.k "▦"] " Add predicate"]
           [:button.popover-item
            {:on-click #(do (close-popover!)
                            (state/open-modal! {:kind :new-rule :domain (:domain p)}))}
            [:span.k "ƒ"] " Add rule"]
           [:button.popover-item
            {:on-click #(do (close-popover!)
                            (state/open-modal! {:kind :new-query :domain (:domain p)}))}
            [:span.k "?"] " Add saved query"]
           [:div.popover-sep]
           [:button.popover-item
            {:title "Drop every predicate in the global schema that has zero facts"
             :on-click (fn []
                         (close-popover!)
                         (let [{:keys [dropped]} (state/cleanup-empty-predicates!)]
                           (js/console.log "cleaned up:" (clj->js dropped))))}
            [:span.k "⌫"] " Clean up empty predicates"]
           [:button.popover-item
            {:on-click #(do (close-popover!)
                            (state/open-modal! {:kind :rename-domain
                                                :domain (:domain p)}))}
            [:span.k "✎"] " Rename…"]
           [:button.popover-item.danger
            {:on-click (fn []
                         (close-popover!)
                         (let [d (state/domain-info (:domain p))]
                           (when (js/confirm
                                  (str "Delete domain " (:label d)
                                       "?\nAll its facts, rules, and schema go with it."))
                             (state/delete-domain! (:domain p)))))}
            [:span.k "⌫"] " Delete domain"]]]

         :move-to
         (let [{:keys [src mover]} p
               others (remove #(= src (:id %)) (state/domains-list))]
           [popover-shell (:anchor p)
            [:div.popover-card
             [:div.popover-title "Move to"]
             (if (empty? others)
               [:div.popover-hint "No other domains."]
               (for [{:keys [id label]} others]
                 ^{:key id}
                 [:button.popover-item
                  {:on-click (fn []
                               (mover src id)
                               (state/switch-domain! id)
                               (close-popover!))}
                  label]))]])

         nil)])))