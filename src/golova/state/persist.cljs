(ns golova.state.persist
  "Persistence boundary: load/save snapshots, migrate from the old
  multi-db shape (v1) to the unified shape (v2), seed first-run users,
  full reset, snapshot import/export, and program (rules+queries)
  import."
  (:require [golova.storage :as storage]
            [golova.state.core :as core :refer [app-state]]
            [golova.state.domain :as domain]
            [golova.state.rebuild :as rebuild]))

;; ---------------------------------------------------------------------------
;; Migration v1 → v2

(defn- old-shape? [snap]
  (and (map? snap) (contains? snap :domains) (nil? (:version snap))))

(defn- migrate-from-v1
  "Convert an old-shape snapshot (multi-domain) into the new unified shape.
  Strategy:
  - Each domain becomes an atom with :domain-label (a domain entity event)
  - Each event in domain D becomes a global event (subject stays as-is)
  - For each subject keyword, emit `:in-domain D` (first domain wins via
    cardinality :one ordering: assert in domain-iteration order, last
    assertion is the binding)
  - Predicate / type / query declarations from D get :domain D
  - Schema conflicts (same predicate name with different argTypes) — first
    wins, others are dropped with a console warning.
  - Rule clauses from D become {:domain D :clause ...} entries

  Returns a v2-shape map ready to assoc into app-state."
  [snap]
  (let [domains (or (:domains snap) {})
        dom-ids (vec (keys domains))
        domain-events
        (vec (for [id dom-ids
                   :let [d (get domains id)
                         lbl (or (:label d) (name id))]]
               (core/mk-event :assert {:triple [id :domain-label lbl]}
                              "migration")))
        user-events
        (vec (for [id dom-ids
                   evt (:events (get domains id))]
               evt))
        in-domain-events
        (let [seen (volatile! #{})
              out (volatile! [])]
          (doseq [id dom-ids
                  evt (:events (get domains id))
                  :let [[e _ _] (:triple evt)]
                  :when (and (keyword? e) (not (@seen e)))]
            (vswap! seen conj e)
            (vswap! out conj
                    (core/mk-event :assert {:triple [e :in-domain id]}
                                   "migration")))
          @out)
        merged-preds (let [out (volatile! [])
                           seen (volatile! #{})]
                       (doseq [id dom-ids
                               p (get-in domains [id :schema :predicates])
                               :let [k [(:name p) (count (:argTypes p))]]]
                         (if (@seen k)
                           (js/console.warn "migration: dropped duplicate predicate"
                                            (clj->js {:name (:name p)
                                                      :from id}))
                           (do (vswap! seen conj k)
                               (vswap! out conj (assoc p :domain id)))))
                       @out)
        merged-types (let [out (volatile! []) seen (volatile! #{})]
                       (doseq [id dom-ids
                               t (get-in domains [id :schema :types])
                               :when (not (@seen (:name t)))]
                         (vswap! seen conj (:name t))
                         (vswap! out conj (assoc t :domain id)))
                       @out)
        merged-queries (let [out (volatile! []) seen (volatile! #{})]
                         (doseq [id dom-ids
                                 q (get-in domains [id :schema :queries])
                                 :when (not (@seen (:name q)))]
                           (vswap! seen conj (:name q))
                           (vswap! out conj (assoc q :domain id)))
                         @out)
        merged-rules (vec (for [id dom-ids
                                clause (get-in domains [id :rules])]
                            {:id (str (random-uuid))
                             :domain id
                             :clause clause}))]
    {:version 2
     :device-id (:device-id snap)
     :current-domain (:current-domain snap)
     :selection (or (:selection snap) {:kind :home})
     :theme (or (:theme snap) :light)
     :expanded (vec (:expanded snap))
     :expanded-subs (vec (:expanded-subs snap))
     :home (merge {:onboarding-collapsed? true} (:home snap))
     :rules merged-rules
     :events (into (into domain-events in-domain-events) user-events)
     :schema {:types merged-types
              :predicates merged-preds
              :queries merged-queries}}))

;; ---------------------------------------------------------------------------
;; First-run starter snapshot

(def ^:private starter-rules
  '[[(ancestor ?x ?y) [?x :parent ?y]]
    [(ancestor ?x ?z) [?x :parent ?y] (ancestor ?y ?z)]])

(defn- starter-snap
  "v2-shape snapshot for first-run users: a single 'Starter' domain with
  the classic ancestor example."
  []
  (let [mk (fn [op tr] {:id (str (random-uuid)) :at 0 :op op
                        :source "starter" :triple tr})]
    {:version 2
     :current-domain :starter
     :selection {:kind :home}
     :theme :light
     :expanded #{:starter}
     :expanded-subs #{}
     :home {:onboarding-collapsed? true}
     :rules (mapv (fn [c] {:id (str (random-uuid))
                           :domain :starter :clause c}) starter-rules)
     :events [(mk :assert [:starter :domain-label "Starter"])
              (mk :assert [:alice :in-domain :starter])
              (mk :assert [:bob   :in-domain :starter])
              (mk :assert [:carol :in-domain :starter])
              (mk :assert [:dave  :in-domain :starter])
              (mk :assert [:alice :parent :bob])
              (mk :assert [:alice :parent :carol])
              (mk :assert [:bob :parent :dave])]
     :schema {:types [{:name "person"
                       :constructors ["alice" "bob" "carol" "dave"]
                       :domain :starter}]
              :predicates [{:name "parent" :argTypes ["person" "person"]
                            :domain :starter}]
              :queries [{:name "alice's descendants"
                         :text "(ancestor :alice ?d)"
                         :domain :starter}
                        {:name "dave's ancestors"
                         :text "(ancestor ?a :dave)"
                         :domain :starter}]}}))

;; ---------------------------------------------------------------------------
;; Load / seed

(defn- load-snap! [snap]
  (swap! app-state assoc
         :current-domain (:current-domain snap)
         :selection (or (:selection snap) {:kind :home})
         :theme (or (:theme snap) :light)
         :expanded (set (:expanded snap))
         :expanded-subs (set (map vec (:expanded-subs snap)))
         :home (merge {:onboarding-collapsed? true} (:home snap))
         :rules (vec (or (:rules snap) []))
         :events (vec (or (:events snap) []))
         :schema (or (:schema snap) {:types [] :predicates [] :queries []})))

(defn load-or-seed! [backend]
  (let [snap (storage/-load backend)]
    (swap! app-state assoc :backend backend)
    (cond
      ;; v2 shape — load directly
      (and snap (= 2 (:version snap)))
      (load-snap! snap)

      ;; v1 (old multi-domain) — migrate then load
      (old-shape? snap)
      (do (js/console.log "migrating localStorage from v1 → v2")
          (let [v2 (migrate-from-v1 snap)]
            (load-snap! v2)))

      ;; first run — seed starter
      :else
      (do (swap! app-state assoc :first-run? true)
          (load-snap! (starter-snap)))))
  (rebuild/rebuild!)
  (core/save!))

;; ---------------------------------------------------------------------------
;; Reset / import

(defn reset-all! []
  (when-let [b (:backend @app-state)] (storage/-clear b))
  (swap! app-state assoc
         :db nil :db-schema nil
         :events [] :rules []
         :schema {:types [] :predicates [] :queries []}
         :rejections [] :build-error nil
         :current-domain nil
         :selection {:kind :home}
         :expanded #{} :expanded-subs #{}
         :top-query {:text "" :result nil}
         :modal nil :popover nil :palette nil
         :home {:onboarding-collapsed? true}
         :error nil)
  (core/save!))

(defn import-snapshot! [snap]
  (let [v2 (cond
             (= 2 (:version snap)) snap
             (old-shape? snap) (migrate-from-v1 snap)
             :else (throw (ex-info "unrecognised snapshot shape" {})))]
    (load-snap! v2)
    (rebuild/rebuild!)
    (core/save!)))

;; ---------------------------------------------------------------------------
;; Program import (rules + queries as a single EDN payload)

(defn apply-program!
  "Import a program file: a map with :rules and/or :queries vectors.

  Each rule:  {:clause [(head ?a ?b) body…] :domain <kw|str>}
  Each query: {:name \"…\" :text \"…\" :domain <kw|str> :pinned? <bool>}

  Rules are APPENDED (not replaced) to the existing rules vector, filed
  under the resolved domain. Queries are upserted by :name within their
  domain (same as `save-query!`). One rebuild at the end."
  [{:keys [rules queries]}]
  (let [new-rules (mapv (fn [{:keys [clause domain]}]
                          {:id (str (random-uuid))
                           :domain (domain/resolve-domain domain)
                           :clause clause})
                        (or rules []))]
    (swap! app-state update :rules (fnil into []) new-rules)
    (doseq [{:keys [name text domain pinned?]} (or queries [])]
      (let [dom (domain/resolve-domain domain)]
        (swap! app-state update-in [:schema :queries]
               (fn [qs]
                 (let [without (vec (remove #(= name (:name %)) (or qs [])))]
                   (conj without
                         (cond-> {:name name :text text :domain dom}
                           pinned? (assoc :pinned? true))))))))
    (rebuild/rebuild!)
    (core/save!)
    {:rules-added (count new-rules)
     :queries-added (count queries)}))
