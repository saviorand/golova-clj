(ns golova.ui.typed
  "Typed-value rendering and editing widgets: type-value (colored cell pill),
  chip-editor (chip-based string list), typed-input (single value picker that
  knows about declared enum types)."
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [golova.state :as state :refer [app-state]]
            [golova.ui.common :refer [atom-link]]))

(defn constructor-type-map
  "Build a {constructor-name-str → type-name-str} lookup for all declared
  types. Domain arg is accepted but ignored (types are global)."
  ([] (into {}
            (for [t (get-in @app-state [:schema :types])
                  c (:constructors t)]
              [c (:name t)])))
  ([_domain-id] (constructor-type-map)))

(defn type-value
  "Render a cell value with a type-aware visual treatment and a hover
  tooltip describing the value's runtime type."
  [ctor-map v]
  (cond
    (and (keyword? v) (get ctor-map (name v)))
    (let [tn (get ctor-map (name v))]
      [:span.type-value {:class (str "type-" tn)
                         :title (str "constructor of type `" tn "` (ref)")}
       (atom-link v)])

    (boolean? v)
    [:span.type-value.type-scalar.type-bool   {:title "bool"} (str v)]
    (integer? v)
    [:span.type-value.type-scalar.type-int    {:title "int (long)"} (str v)]
    (number? v)
    [:span.type-value.type-scalar.type-float  {:title "float / double"} (str v)]
    (string? v)
    [:span.type-value.type-scalar.type-string {:title "string"} v]

    (keyword? v)
    [:span {:title "atom (ref) — untyped keyword"} (atom-link v)]

    :else (atom-link v)))

(defn chip-editor
  "Pill-based list editor. `state-atom` holds a vector of strings.
  Enter / comma commits; Backspace on empty deletes the last chip."
  [{:keys [state-atom placeholder suggestions-fn]}]
  (let [input (r/atom "")
        commit (fn [v]
                 (let [v (str/trim (or v ""))]
                   (when (and (seq v) (not (some #{v} @state-atom)))
                     (swap! state-atom conj v)
                     (reset! input ""))))]
    (fn [{:keys [placeholder suggestions-fn]}]
      (let [filtered-suggestions
            (when suggestions-fn
              (let [q (str/lower-case @input)
                    taken (set @state-atom)]
                (->> (suggestions-fn)
                     (map name)
                     (remove taken)
                     (filter #(str/includes? (str/lower-case %) q))
                     (take 12))))]
        [:div.chip-editor
         [:div.chip-list
          (for [[i it] (map-indexed vector @state-atom)]
            ^{:key (str i "-" it)}
            [:span.chip
             [:span.chip-text it]
             [:button.chip-x
              {:on-click #(swap! state-atom (fn [xs] (vec (remove #{it} xs))))
               :title "Remove"} "×"]])
          [:input.chip-input
           {:value @input
            :placeholder (or placeholder "Type and press Enter…")
            :on-change #(reset! input (.. % -target -value))
            :on-key-down (fn [e]
                           (cond
                             (or (= "Enter" (.-key e)) (= "," (.-key e)))
                             (do (.preventDefault e) (commit @input))
                             (and (= "Backspace" (.-key e))
                                  (str/blank? @input)
                                  (seq @state-atom))
                             (swap! state-atom (fn [xs] (vec (drop-last xs))))))}]]
         (when (seq filtered-suggestions)
           [:div.chip-suggestions
            [:span.cs-label "Suggest:"]
            (for [s filtered-suggestions]
              ^{:key s}
              [:button.chip.suggest
               {:on-click #(commit s)}
               "+ " s])])]))))

(defn- type-options
  "Constructor list for a declared type, or nil."
  [tname]
  (let [t (first (filter #(= tname (:name %))
                         (get-in @app-state [:schema :types])))]
    (when t (vec (:constructors t)))))

(defn typed-input
  "A controlled input for a single typed value. `state` is an r/atom holding
  {:val raw-string :new-mode? bool}. For declared enum types renders a
  <select> with a '+ new …' sentinel that swaps to a text input on click."
  [{:keys [arg-type state placeholder on-enter autofocus?]}]
  (let [opts (type-options arg-type)
        st @state
        in-new? (:new-mode? st)
        commit-new (fn [v]
                     (let [v (str/trim (or v ""))]
                       (when (seq v)
                         (state/extend-type! arg-type v)
                         (swap! state assoc :val v :new-mode? false))))]
    (cond
      (and opts (not in-new?))
      [:select.typed
       {:value (or (:val st) "")
        :auto-focus autofocus?
        :on-change (fn [e]
                     (let [v (.. e -target -value)]
                       (if (= v "__new__")
                         (swap! state assoc :new-mode? true :val "")
                         (swap! state assoc :val v))))}
       [:option {:value ""} (str "— " arg-type " —")]
       (for [o opts] ^{:key o} [:option {:value o} o])
       [:option {:value "__new__"} "+ new …"]]

      in-new?
      [:input.typed.new
       {:auto-focus true
        :placeholder (str "new " arg-type)
        :default-value (:val st)
        :on-key-down (fn [e]
                       (cond
                         (= "Enter" (.-key e))
                         (do (.preventDefault e)
                             (commit-new (.. e -target -value))
                             (when on-enter (on-enter)))
                         (= "Escape" (.-key e))
                         (swap! state assoc :new-mode? false :val "")))
        :on-blur #(commit-new (.. % -target -value))}]

      :else
      [:input.typed
       {:type (if (= "int" arg-type) "number" "text")
        :placeholder (or placeholder arg-type)
        :auto-focus autofocus?
        :value (or (:val st) "")
        :on-change #(swap! state assoc :val (.. % -target -value))
        :on-key-down (fn [e]
                       (when (and on-enter (= "Enter" (.-key e)))
                         (.preventDefault e)
                         (on-enter)))}])))
