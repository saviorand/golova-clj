(ns golova.ui.topbar
  "Top bar — Datalog query input with antd Input.Search, Sync button,
  and breadcrumb-style navigation context."
  (:require [clojure.string :as str]
            [reagent.core :as r]
            ["@ant-design/icons" :as icons]
            [golova.state :as state :refer [app-state]]
            [golova.ui.common :refer [atom-link]]
            [golova.ui.antd :as antd]))

(def ^:private result-row-cap 200)

(defn- sync-button []
  (let [{:keys [backend-kind sync-state]} @app-state]
    (when (= :git backend-kind)
      (let [status (or (:status sync-state) :idle)
            err    (:error-msg sync-state)]
        [:> (.-Button antd/antd)
         {:type    (if (= :error status) "default" "default")
              :icon    (r/as-element [:> (.-SyncOutlined icons)])
              :loading (contains? #{:pulling :pushing} status)
              :danger  (= :error status)
              :title   (or err "Pull from GitHub, then push local changes")
              :onClick (fn [] (-> (state/sync!) (.catch (fn [_] nil))))}
         (case status
           :idle    "Sync"
           :pulling "Pulling…"
           :pushing "Pushing…"
           :error   "Sync (error)"
           "Sync")]))))

(defn- results-display [result]
  (when result
    [:div.topbar-results
     (cond
       (:error result)
       [:> (.-Alert antd/antd)
        {:type "error"
             :message (:error result)
             :banner true
             :showIcon true}]

       (empty? (:rows result))
       [:div.results-empty
        [:> (.-Empty antd/antd)
         {:description "No solutions"
              :image       (.-PRESENTED_IMAGE_SIMPLE antd/Empty)}]]

       :else
       (let [rows (:rows result)
             total (count rows)
             shown (take result-row-cap rows)]
         [:div.results-container
          [:div.results-summary
           [:> (.-Badge antd/antd)
            {:count total
                 :showZero true
                 :overflowCount 999
                 :style #js {:backgroundColor "var(--ant-color-primary)"}}
            [:span.results-label
             " solution" (when (not= 1 total) "s")]]
           (when (> total result-row-cap)
             [:span.results-trunc " · showing first " result-row-cap])]
          [:div.results-rows
           (for [[i row] (map-indexed vector shown)]
             ^{:key i}
             [:div.result-row
              (for [[k v] (map vector (:vars result) row)]
                ^{:key k}
                [:span.result-binding
                 [:span.result-var k]
                 " = "
                 [:span.result-val [atom-link v]]])])]]))]))

(defn topbar []
  (let [{:keys [top-query]} @app-state
        text (:text top-query)
        result (:result top-query)
        run! (fn []
               (let [t (str/trim text)]
                 (if (str/blank? t)
                   (swap! app-state assoc :top-query {:text "" :result nil})
                   (swap! app-state assoc-in [:top-query :result]
                          (state/run-query t)))))]
    [:div.topbar-wrapper
     [:div.topbar
      [:button.hamburger
       {:title "Open sidebar"
        :on-click #(state/toggle-sidebar-mobile!)}
       "☰"]
      [:div.topbar-search
       [:> (.-Input antd/antd)
        {:prefix     (r/as-element [:> (.-SearchOutlined icons)])
             :placeholder "Ask. e.g. parent(?p, ?c)"
             :spellCheck  "false"
             :value       text
             :allowClear  true
             :size        "large"
             :onChange     #(swap! app-state assoc-in [:top-query :text]
                                   (.. % -target -value))
             :onKeyDown   (fn [e]
                            (when (= "Enter" (.-key e))
                              (.preventDefault e)
                              (run!)))
             :addonAfter  (r/as-element
                           [:> (.-Button antd/antd)
                            {:type "primary"
                                 :onClick run!}
                            "Run"])}]]
      [sync-button]]
     [results-display result]]))
