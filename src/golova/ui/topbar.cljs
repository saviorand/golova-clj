(ns golova.ui.topbar
  "Top query bar — runs Datalog against the global db and renders solutions
  inline. Bound to ⌘K by app-level keybindings."
  (:require [clojure.string :as str]
            [golova.state :as state :refer [app-state]]
            [golova.ui.common :refer [atom-link]]))

(def ^:private result-row-cap 200)

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
    [:div
     [:div.topbar
      [:input.query
       {:placeholder "Ask. e.g. parent(?p, ?c)"
        :spellCheck "false"
        :value text
        :on-change #(swap! app-state assoc-in [:top-query :text] (.. % -target -value))
        :on-key-down (fn [e]
                       (when (= "Enter" (.-key e))
                         (.preventDefault e)
                         (run!)))}]
      [:button.primary {:on-click run!} "Run"]]
     (when result
       [:div.top-result
        (cond
          (:error result)
          [:div.results.err (:error result)]

          (empty? (:rows result))
          [:div.results
           [:div.summary "no solutions"]]

          :else
          (let [rows (:rows result)
                total (count rows)
                shown (take result-row-cap rows)]
            [:div.results
             [:div.summary total " solution" (when (not= 1 total) "s")
              (when (> total result-row-cap)
                [:span.trunc " · showing first " result-row-cap])]
             (for [[i row] (map-indexed vector shown)]
               ^{:key i}
               [:div.row
                (for [[k v] (map vector (:vars result) row)]
                  ^{:key k}
                  [:span.b [:span.k k] " = " [:span.v (atom-link v)]])])]))])]))
