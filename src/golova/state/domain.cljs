(ns golova.state.domain
  "Domains are first-class atoms in the unified db. This namespace covers
  the read-only domain queries, the domain-aware accessors over the
  schema declarations, and the domain CRUD mutations."
  (:require [clojure.string :as str]
            [datahike.core :as d]
            [golova.state.core :as core :refer [app-state]]
            [golova.state.rebuild :as rebuild]))

;; ---------------------------------------------------------------------------
;; Read-only

(defn domains-list
  "Sorted seq of domain entities in the current db: {:id :label :parent}.
  Top-level domains (no :domain-parent) come first."
  []
  (let [db (:db @app-state)]
    (if-not db
      []
      (let [ident-of (fn [eid]
                       (or (:v (first (d/datoms db :eavt eid :db/ident))) eid))]
        (->> (d/datoms db :aevt :domain-label)
             (mapv (fn [d]
                     (let [id (ident-of (:e d))
                           parent (some-> db (d/datoms :eavt (:e d) :domain-parent)
                                          first :v ident-of)]
                       {:id id
                        :label (:v d)
                        :parent parent})))
             (sort-by (juxt :parent :label)))))))

(defn domain-info
  "Lookup one domain's {:id :label :parent} or nil."
  [domain-id]
  (when domain-id
    (first (filter #(= domain-id (:id %)) (domains-list)))))

(defn subdomains-of
  "Vec of domains whose :domain-parent is `parent-id`. Top-level domains
  are returned when `parent-id` is nil."
  [parent-id]
  (vec (filter #(= parent-id (:parent %)) (domains-list))))

(defn ancestor-of?
  "True when `maybe-ancestor` is `id` or appears above it via :domain-parent."
  [maybe-ancestor id]
  (let [by-id (into {} (map (juxt :id identity) (domains-list)))]
    (loop [cur id]
      (cond
        (nil? cur) false
        (= maybe-ancestor cur) true
        :else (recur (:parent (get by-id cur)))))))

(defn atoms-in-domain
  "Sorted seq of atom keywords whose :in-domain is `domain-id`."
  [domain-id]
  (let [db (:db @app-state)]
    (when (and db domain-id)
      (let [dom-eid (some-> (d/datoms db :avet :db/ident domain-id) first :e)]
        (when dom-eid
          (->> (d/datoms db :avet :in-domain dom-eid)
               (keep (fn [d]
                       (some-> (d/datoms db :eavt (:e d) :db/ident) first :v)))
               sort))))))

(defn declared-types-in
  "User-declared types filed under domain `domain-id`."
  [domain-id]
  (filter #(= domain-id (:domain %)) (get-in @app-state [:schema :types])))

(defn declared-predicates-in
  "User-declared predicates filed under domain `domain-id`."
  [domain-id]
  (filter #(= domain-id (:domain %)) (get-in @app-state [:schema :predicates])))

(defn queries-in
  "Saved queries filed under domain `domain-id`."
  [domain-id]
  (filter #(= domain-id (:domain %)) (get-in @app-state [:schema :queries])))

(defn sources-in
  "External-source declarations filed under domain `domain-id`."
  [domain-id]
  (filter #(= domain-id (:domain %)) (get-in @app-state [:schema :sources])))

(defn rules-in
  "Rules filed under domain `domain-id`."
  [domain-id]
  (filter #(= domain-id (:domain %)) (:rules @app-state)))

;; ---------------------------------------------------------------------------
;; CRUD

(defn- fresh-domain-id
  "Pick a unique keyword id for a new domain, derived from `label`."
  [label]
  (let [base (let [b (core/safe-name label)] (if (str/blank? b) "domain" b))
        taken (set (map :id (domains-list)))]
    (loop [id (keyword base) i 2]
      (if (taken id)
        (recur (keyword (str base "-" i)) (inc i))
        id))))

(defn create-domain!
  "Create a new domain atom with `label`. Optional `parent` makes it a
  subdomain. Returns the new domain's keyword id."
  ([label] (create-domain! label nil))
  ([label parent]
   (let [id (fresh-domain-id label)
         evts (cond-> [(core/mk-event :assert {:triple [id :domain-label label]} "create-domain")]
                parent (conj (core/mk-event :assert {:triple [id :domain-parent parent]}
                                            "create-domain")))]
     (rebuild/append-events! evts)
     (swap! app-state assoc :current-domain id)
     (core/save!)
     id)))

(defn delete-domain!
  "Remove a domain atom + retract :in-domain for every atom that pointed at it.
  Also detach any subdomains (clears their :domain-parent so they become
  top-level) — never delete subdomains. The atoms in this domain are not
  deleted either (they become domainless)."
  [id]
  (let [db (:db @app-state)
        dom-eid (some-> (d/datoms db :avet :db/ident id) first :e)]
    (when dom-eid
      (let [member-eids (mapv :e (d/datoms db :avet :in-domain dom-eid))
            member-idents (keep (fn [eid]
                                  (some-> db (d/datoms :eavt eid :db/ident) first :v))
                                member-eids)
            child-domains (map :id (subdomains-of id))
            evts (concat
                   (for [c child-domains]
                     (core/mk-event :retract {:triple [c :domain-parent id]}))
                   (for [m member-idents]
                     (core/mk-event :retract {:triple [m :in-domain id]}))
                   [(core/mk-event :retract {:triple [id :domain-label
                                                       (-> (domain-info id) :label)]})])]
        (rebuild/append-events! evts))))
  (swap! app-state update :schema
         (fn [s]
           (-> s
               (update :types (fn [ts] (mapv #(if (= id (:domain %))
                                                 (dissoc % :domain) %) ts)))
               (update :predicates (fn [ps] (mapv #(if (= id (:domain %))
                                                     (dissoc % :domain) %) ps)))
               (update :queries (fn [qs] (mapv #(if (= id (:domain %))
                                                  (dissoc % :domain) %) qs))))))
  (when (= id (core/current-id))
    (swap! app-state assoc :current-domain
           (-> (domains-list) first :id)))
  (rebuild/rebuild!)
  (core/save!))

(defn rename-domain! [id new-label]
  (let [old (some-> (domain-info id) :label)]
    (when old
      (rebuild/append-events!
        [(core/mk-event :retract {:triple [id :domain-label old]})
         (core/mk-event :assert {:triple [id :domain-label new-label]} "rename-domain")]))))

(defn move-domain-parent!
  "Set or clear the :domain-parent on `id`. `new-parent` of nil unparents
  the domain (it becomes top-level). Refuses cycles — a domain may not
  become its own ancestor."
  [id new-parent]
  (cond
    (nil? id) nil
    (= id new-parent) (throw (ex-info "a domain can't be its own parent"
                                      {:id id}))
    (and new-parent (ancestor-of? id new-parent))
    (throw (ex-info "cycle: target is a descendant of this domain"
                    {:id id :new-parent new-parent}))
    :else
    (let [db (:db @app-state)
          dom-eid (some-> (d/datoms db :avet :db/ident id) first :e)
          old-parent-eid (some-> db (d/datoms :eavt dom-eid :domain-parent)
                                 first :v)
          old-parent (when old-parent-eid
                       (some-> db (d/datoms :eavt old-parent-eid :db/ident)
                               first :v))
          evts (cond-> []
                 old-parent
                 (conj (core/mk-event :retract {:triple [id :domain-parent old-parent]}))
                 new-parent
                 (conj (core/mk-event :assert
                                      {:triple [id :domain-parent new-parent]}
                                      "move-domain-parent")))]
      (when (seq evts) (rebuild/append-events! evts)))))

(defn find-or-create-domain!
  "Return the id of an existing domain matching `label`, or create one and
  return the new id. Match is exact label OR id-keyword derived from label."
  [label]
  (let [target-id (-> label core/safe-name keyword)
        existing (->> (domains-list)
                      (filter (fn [d] (or (= target-id (:id d))
                                          (= label (:label d)))))
                      first
                      :id)]
    (or existing (create-domain! label))))

(defn resolve-domain
  "Resolve a domain reference from a plan/program file:
    string  → find-or-create by label
    keyword → assume it's an existing id
    nil     → fall back to current-domain"
  [d]
  (cond
    (string? d)  (find-or-create-domain! d)
    (keyword? d) d
    :else        (core/current-id)))
