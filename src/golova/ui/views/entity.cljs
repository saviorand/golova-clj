(ns golova.ui.views.entity
  "Entity (atom) page: hero, markdown note, facts grouped by attribute,
  symmetric-dedupe toggle, add-relation picker, wikilink backlinks."
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [golova.state :as state :refer [app-state]]
            [golova.ui.common :refer [atom-link pred-link fmt-val markdown]]
            [golova.ui.typed :refer [typed-input]]))

(defn- entity-add-form
  "Inline add-fact form for an entity. `owning-domain` auto-assigns
  :in-domain on new subjects."
  [owning-domain entity attr role arg-types]
  (let [val (r/atom {:val ""})
        err (r/atom nil)]
    (fn [_ entity attr role arg-types]
      (let [other-type (case role
                         :subject (or (second arg-types) "atom")
                         :object  (or (first arg-types)  "atom"))
            commit (fn []
                     (try
                       (let [v (state/coerce-value other-type (:val @val))
                             triple (case role
                                      :subject [entity attr v]
                                      :object  [v attr entity])]
                         (state/assert-triple! triple "form" owning-domain)
                         (reset! val {:val ""})
                         (reset! err nil))
                       (catch :default ex
                         (reset! err (.-message ex)))))
            input [typed-input {:arg-type other-type
                                :state val :placeholder other-type
                                :on-enter commit}]
            fixed [:span.entity-fixed (fmt-val entity)]]
        [:div.entity-add
         [:div.row
          (if (= role :subject) fixed input)
          [:span.arrow "→"]
          (if (= role :subject) input fixed)
          [:button.primary.small {:on-click commit :title "Add (Enter)"} "+"]]
         (when @err [:div.err.mini @err])]))))

(defn- entity-relation-roles
  "Set of {:subject :object} roles the entity plays in these triples."
  [entity triples]
  (cond-> #{}
    (some (fn [[e _ _]] (= e entity)) triples) (conj :subject)
    (some (fn [[_ _ v]] (= v entity)) triples) (conj :object)))

(defn- canonical-symmetric
  "Drop self-loops and pick one direction for symmetric pairs."
  [triples]
  (let [present (set (map (juxt first second #(nth % 2)) triples))]
    (filter
      (fn [[e a v]]
        (cond
          (= e v) false
          (and (contains? present [v a e])
               (pos? (compare (str e) (str v))))
          false
          :else true))
      triples)))

(defn- relation-form-block
  [domain-id entity attr arg-types roles]
  [:div.add-zone
   (for [role (sort roles)]
     ^{:key role}
     [entity-add-form domain-id entity attr role arg-types])])

(defn- entity-note-block
  "Markdown note section."
  [_owning-domain entity]
  (let [editing? (r/atom false)
        draft (r/atom nil)]
    (fn [_owning-domain entity]
      (let [note (state/entity-note entity)]
        (if @editing?
          [:div.entity-note.editing
           [:textarea.note-edit
            {:auto-focus true
             :placeholder "Markdown notes about this entity. [[wikilinks]] are linked atoms."
             :default-value (or @draft note "")
             :on-change #(reset! draft (.. % -target -value))}]
           [:div.note-controls
            [:button.primary.small
             {:on-click (fn []
                          (state/set-note! entity (or @draft note ""))
                          (reset! editing? false)
                          (reset! draft nil))}
             "Save"]
            [:button.ghost.small
             {:on-click (fn [] (reset! editing? false) (reset! draft nil))}
             "Cancel"]]]
          (if (and note (not (str/blank? note)))
            [:div.entity-note
             [markdown note]
             [:div.note-controls
              [:button.ghost.small {:on-click #(reset! editing? true)} "Edit note"]]]
            [:button.ghost.small.add-note
             {:on-click #(reset! editing? true)}
             "+ Add note"]))))))

(defn entity-view [e]
  (let [ui-state (r/atom {:open-add nil :dedupe? true})]
    (fn [e]
      (let [domain-id (or (state/entity-domain e) (state/current-id))
            triples (state/entity-mentions e)
            triples (remove #(= :note (second %)) triples)
            by-attr (group-by second triples)
            declared-preds (->> (get-in @app-state [:schema :predicates])
                                (filter #(= 2 (count (:argTypes %)))))
            seen-attrs (set (keys by-attr))
            unseen-preds (->> declared-preds
                              (remove #(seen-attrs (keyword (:name %)))))
            dedupe? (:dedupe? @ui-state)]
        [:div.view
         [:div.entity-hero
          [:div.avatar (str/upper-case (subs (fmt-val e) 0 1))]
          [:div
           [:div.mono.title (fmt-val e)]
           [:div.subtitle (count triples) " mention"
            (when (not= 1 (count triples)) "s")]]
          [:div.entity-controls
           [:button.ghost.small
            {:title (if dedupe?
                      "Currently hiding self-loops and one direction of symmetric pairs"
                      "Showing every triple, including duplicates and self-loops")
             :on-click #(swap! ui-state update :dedupe? not)}
            (if dedupe? "showing canonical" "showing all")]]]

         [entity-note-block domain-id e]

         (if (empty? triples)
           [:div.empty
            [:div "No facts mention this entity yet."]
            [:div {:style {:margin-top "8px" :color "var(--dim)"}}
             "Use the picker below to add your first relation."]]
           (for [[attr trs] (sort-by (comp str first) by-attr)]
             ^{:key (str attr)}
             (let [decl (first (filter #(and (= (name attr) (:name %))
                                             (= 2 (count (:argTypes %))))
                                       (get-in @app-state [:schema :predicates])))
                   arg-types (when decl (:argTypes decl))
                   roles (entity-relation-roles e trs)
                   shown (if dedupe? (canonical-symmetric trs) trs)
                   hidden (- (count trs) (count shown))]
               [:div.relation-group
                [:h3.rel-attr
                 [:span.rel-name [pred-link attr]]
                 [:span.dim " — " (count shown)
                  (when (pos? hidden)
                    [:span.tiny-hint
                     " (" hidden " " (if (= 1 hidden) "duplicate" "duplicates")
                     " filtered)"])]
                 (when decl [:span.pill.declared.mini "declared"])]
                (for [[i [ee _ vv :as tr]] (map-indexed vector shown)]
                  ^{:key (str (pr-str tr))}
                  [:div.fact-row
                   [:span (atom-link ee)]
                   [:span.arrow "→"]
                   [:span (atom-link vv)]
                   [:button.ghost.danger.remove
                    {:on-click #(state/retract-triple! (vec tr))
                     :title "Retract"} "×"]])
                [relation-form-block domain-id e attr arg-types roles]])))

         (let [bl (state/note-backlinks e)]
           (when (seq bl)
             [:div.backlinks
              [:h3.section-h "Linked from notes"]
              (for [[from-ent text] bl]
                ^{:key from-ent}
                [:div.backlink-row
                 [:div.bl-head
                  (atom-link from-ent)]
                 [:div.bl-snippet
                  (let [s (str/replace text #"\s+" " ")]
                    (if (> (count s) 140) (str (subs s 0 140) "…") s))]])]))

         (when (seq declared-preds)
           (let [open-add (:open-add @ui-state)]
             [:div.new-relation
              [:h3.section-h "Add another relation"]
              [:div.pred-picker
               (for [p unseen-preds]
                 ^{:key (:name p)}
                 [:button.chip
                  {:class (when (and open-add (= (:name p) (:name open-add))) "active")
                   :on-click #(swap! ui-state assoc :open-add
                                     {:name (:name p) :arg-types (:argTypes p)
                                      :role :subject})}
                  (:name p) "/" (count (:argTypes p))])
               (when (empty? unseen-preds)
                 [:span.dim {:style {:padding "4px 0"}}
                  "All declared predicates already have facts for this entity."])]
              (when-let [{:keys [name arg-types role]} open-add]
                [:div.add-inline
                 [:div.role-toggle
                  [:button {:class (when (= role :subject) "active")
                            :on-click #(swap! ui-state assoc-in [:open-add :role] :subject)}
                   "as subject"]
                  [:button {:class (when (= role :object) "active")
                            :on-click #(swap! ui-state assoc-in [:open-add :role] :object)}
                   "as object"]
                  [:button.ghost {:on-click #(swap! ui-state assoc :open-add nil)} "Cancel"]]
                 [entity-add-form domain-id e (keyword name) role arg-types]])]))]))))
