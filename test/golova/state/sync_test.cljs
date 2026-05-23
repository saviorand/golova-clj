(ns golova.state.sync-test
  "Tests for the snapshot↔files round-trip and deterministic-id derivation.
  Pure logic only — no DOM, no fetch, no app boot."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [cljs.test :refer-macros [deftest is testing run-tests]]
            [cljs.reader :as reader]
            [golova.state.persist :as persist]
            [golova.state.sync :as sync]))

;; ---------------------------------------------------------------------------
;; Fixtures

(def ^:private sample-snap
  "A small v2 snapshot exercising two domains, a sub-domain, ref/scalar
  predicate mix, rules with arity 1 + 2, and events of every flavour
  (assert, retract, in-domain, domain-label, domain-parent, note)."
  {:version 2
   :device-id "dev-1"               ;; serializable carries this; sync excludes
   :current-domain :people
   :selection {:kind :rules}
   :theme :light
   :expanded #{:people}
   :expanded-subs #{[:people :rules]}
   :home {:onboarding-collapsed? true}
   :rules [{:id "r1" :domain :people
            :clause '[(ancestor ?a ?d) [?a :parent ?d]]}
           {:id "r2" :domain :people
            :clause '[(ancestor ?a ?d) [?a :parent ?p] (ancestor ?p ?d)]}
           {:id "r3" :domain :books
            :clause '[(priority-read ?b) [?b :recommended-by ?p]]}]
   :events [{:id "e1" :op :assert :at 1 :source "seed"
             :triple [:people :domain-label "People"]}
            {:id "e2" :op :assert :at 2 :source "seed"
             :triple [:books :domain-label "Books"]}
            {:id "e3" :op :assert :at 3 :source "seed"
             :triple [:fiction :domain-label "Fiction"]}
            {:id "e4" :op :assert :at 4 :source "seed"
             :triple [:fiction :domain-parent :books]}
            {:id "e5" :op :assert :at 5 :source "seed"
             :triple [:alice :in-domain :people]}
            {:id "e6" :op :assert :at 6 :source "seed"
             :triple [:bob :in-domain :people]}
            {:id "e7" :op :assert :at 7 :source "ui"
             :triple [:alice :parent :bob]}
            {:id "e8" :op :assert :at 8 :source "ui"
             :triple [:alice :note "first person"]}
            {:id "e9" :op :assert :at 9 :source "seed"
             :triple [:dune :in-domain :books]}
            {:id "e10" :op :assert :at 10 :source "ui"
             :triple [:dune :recommended-by :alice]}]
   :schema {:types       [{:name "person" :constructors ["alice" "bob"]
                           :domain :people}]
            :predicates  [{:name "parent" :argTypes ["person" "person"]
                           :domain :people}
                          {:name "recommended-by" :argTypes ["atom" "atom"]
                           :domain :books}]
            :queries     [{:name "ancestors" :text "(ancestor ?a ?d)"
                           :domain :people :pinned? true}]}})

;; ---------------------------------------------------------------------------
;; snapshot->files: shape checks

(deftest snapshot->files-emits-expected-paths
  (let [files (sync/snapshot->files sample-snap)
        paths (set (keys files))]
    (testing "top-level meta"
      (is (contains? paths "meta.edn")))
    (testing "domain folders for every domain (including sub-domain)"
      (is (set/subset? #{"domains/people/domain.edn"
                         "domains/people/rules.edn"
                         "domains/people/types.edn"
                         "domains/people/predicates.edn"
                         "domains/people/queries.edn"
                         "domains/people/events.edn"}
                       paths))
      (is (set/subset? #{"domains/books/domain.edn"
                         "domains/books/rules.edn"
                         "domains/books/events.edn"}
                       paths))
      (is (set/subset? #{"domains/fiction/domain.edn"
                         "domains/fiction/events.edn"}
                       paths))
      (testing "no domain.edn for a domain absent from snapshot"
        (is (not (contains? paths "domains/tasks/domain.edn")))))))

(deftest meta-edn-excludes-device-and-bearer
  (let [files (sync/snapshot->files sample-snap)
        meta (reader/read-string (get files "meta.edn"))]
    (testing ":device-id is per-device, never in synced files"
      (is (not (contains? meta :device-id))))
    (testing ":sync-config (with bearer token) is never serialised here"
      (is (not (contains? meta :sync-config)))
      (is (not (contains? meta :backend-kind))))))

(deftest domain-edn-captures-label-and-parent
  (let [files (sync/snapshot->files sample-snap)
        people  (reader/read-string (get files "domains/people/domain.edn"))
        fiction (reader/read-string (get files "domains/fiction/domain.edn"))]
    (is (= "People"  (:label  people)))
    (is (= nil       (:parent people)))
    (is (= "Fiction" (:label  fiction)))
    (is (= :books    (:parent fiction)))))

(deftest events-edn-strips-domain-meta-and-buckets-by-subject-domain
  (let [files (sync/snapshot->files sample-snap)
        ev-people  (reader/read-string (get files "domains/people/events.edn"))
        ev-books   (reader/read-string (get files "domains/books/events.edn"))
        ev-fiction (reader/read-string (get files "domains/fiction/events.edn"))
        all-attrs  (fn [evs] (set (map #(second (:triple %)) evs)))]
    (testing "domain-label/parent events live in domain.edn, not events.edn"
      (is (not (some #{:domain-label :domain-parent} (all-attrs ev-people))))
      (is (not (some #{:domain-label :domain-parent} (all-attrs ev-books))))
      (is (not (some #{:domain-label :domain-parent} (all-attrs ev-fiction)))))
    (testing "alice/bob facts go under people"
      (is (some #(= [:alice :parent :bob] (:triple %)) ev-people))
      (is (some #(= [:alice :note "first person"] (:triple %)) ev-people)))
    (testing "dune fact goes under books"
      (is (some #(= [:dune :recommended-by :alice] (:triple %)) ev-books)))
    (testing "fiction's events file exists but empty (sub-domain with no facts)"
      (is (= [] ev-fiction)))))

(deftest rules-edn-strips-id-and-domain
  (let [files (sync/snapshot->files sample-snap)
        rules (reader/read-string (get files "domains/people/rules.edn"))]
    (is (every? vector? rules))
    (is (every? (fn [c] (not (and (map? c) (contains? c :id)))) rules))))

;; ---------------------------------------------------------------------------
;; Determinism — same snapshot → same bytes

(deftest snapshot->files-is-deterministic
  (let [a (sync/snapshot->files sample-snap)
        b (sync/snapshot->files sample-snap)]
    (is (= a b)))
  (testing "shuffling input collections still produces the same files"
    (let [shuffled (-> sample-snap
                       (update :rules reverse)
                       (update :events reverse)
                       (update-in [:schema :types] reverse)
                       (update-in [:schema :predicates] reverse))
          a (sync/snapshot->files sample-snap)
          b (sync/snapshot->files shuffled)]
      ;; Events are sorted by :at so reversing keeps order. Rules and schema
      ;; are sorted by name/clause text so reversal is normalised out.
      (is (= a b)))))

;; ---------------------------------------------------------------------------
;; Round-trip — the load-bearing invariant

(defn ^:private normalise
  "Bring a snapshot into the comparable form: drop fields the sync layer
  intentionally doesn't carry, normalise event ordering, treat any rule
  :id as opaque (snapshot->files strips, files->snapshot regenerates)."
  [snap]
  (-> snap
      (dissoc :device-id)
      (update :expanded set)
      (update :expanded-subs (fn [es] (set (map vec es))))
      ;; Rules: drop :id (regenerated on import), sort by clause
      (update :rules (fn [rs]
                       (->> rs
                            (map #(select-keys % [:domain :clause]))
                            (sort-by (juxt :domain (comp pr-str :clause)))
                            vec)))
      ;; Events: drop :id and :source for comparison (id may be regenerated
      ;; deterministically for synth events; source is preserved by sync).
      (update :events (fn [es]
                        (->> es
                             (map (fn [e] (select-keys e [:op :triple :at])))
                             (sort-by (juxt :at (comp pr-str :triple)))
                             vec)))
      ;; Schema collections: sort for stable comparison
      (update :schema
              (fn [s]
                (-> s
                    (update :types      #(vec (sort-by :name %)))
                    (update :predicates #(vec (sort-by (juxt :name (comp count :argTypes)) %)))
                    (update :queries    #(vec (sort-by :name %))))))))

(deftest round-trip-identity
  (let [files (sync/snapshot->files sample-snap)
        {:keys [snapshot warnings]} (sync/files->snapshot files)]
    (testing "no warnings on a clean snapshot"
      (is (empty? warnings)))
    (testing "snapshot semantics survive a round-trip"
      (is (= (normalise sample-snap) (normalise snapshot))))))

(deftest round-trip-twice-converges
  (testing "files → snapshot → files is idempotent (same bytes both times)"
    (let [files1 (sync/snapshot->files sample-snap)
          snap2  (:snapshot (sync/files->snapshot files1))
          files2 (sync/snapshot->files snap2)]
      (is (= files1 files2)))))

;; ---------------------------------------------------------------------------
;; Open Question #3 — deterministic event IDs on hand-edits

(deftest hand-edited-events-get-deterministic-ids
  (testing "events.edn with no :id gets the same id on every re-import"
    (let [files (sync/snapshot->files sample-snap)
          ;; Take the people events file, strip :id from every event,
          ;; re-emit, and pull twice — ids must match.
          ev-text (get files "domains/people/events.edn")
          ev-vec  (reader/read-string ev-text)
          stripped (mapv #(dissoc % :id) ev-vec)
          re-emitted (pr-str stripped)
          files'  (assoc files "domains/people/events.edn" re-emitted)
          {snap1 :snapshot} (sync/files->snapshot files')
          {snap2 :snapshot} (sync/files->snapshot files')
          ids1   (->> (:events snap1) (filter #(= :people (-> % :triple first))) (map :id))
          ids2   (->> (:events snap2) (filter #(= :people (-> % :triple first))) (map :id))]
      (is (= ids1 ids2) "two imports of the same id-less file produce the same ids"))))

(deftest hand-edited-rules-get-fresh-ids-on-import
  (testing "rules.edn is the bare-clause form — :id is regenerated on import"
    (let [files (sync/snapshot->files sample-snap)
          {:keys [snapshot]} (sync/files->snapshot files)]
      (is (every? :id (:rules snapshot))
          "every rule has an :id after import even though rules.edn carries none"))))

;; ---------------------------------------------------------------------------
;; Domain assignment for events whose subject is itself a domain
;; (proposal Open Question — :domain-label / :domain-parent stay under that
;; domain's events.edn, not orphaned.)

(deftest domain-meta-stays-with-its-domain
  (testing "synthesized domain.edn → :domain-label event is reattached under that domain"
    (let [files (sync/snapshot->files sample-snap)
          {:keys [snapshot]} (sync/files->snapshot files)
          fic-evs (->> (:events snapshot)
                       (filter #(= [:fiction :domain-label "Fiction"]
                                   (:triple %))))]
      (is (seq fic-evs))
      (is (= :assert (-> fic-evs first :op))))))

;; ---------------------------------------------------------------------------
;; Edge cases

(deftest empty-snapshot-still-emits-meta
  (let [empty-snap {:version 2 :events [] :rules []
                    :schema {:types [] :predicates [] :queries []}}
        files (sync/snapshot->files empty-snap)]
    (is (contains? files "meta.edn"))
    (is (= 1 (count files)) "no domain files when there are no domains")))

(deftest missing-meta-throws
  (testing "missing meta.edn is a hard error — there's no snapshot without it"
    (is (thrown? :default
                 (sync/files->snapshot {"domains/x/domain.edn" "{:id :x}"})))))

(deftest empty-files-vs-no-meta-distinction
  (testing "completely empty files map throws (no snapshot at all)"
    (is (thrown? :default (sync/files->snapshot {}))))
  (testing "files without meta.edn throw — caller must treat first-push case"
    (is (thrown? :default
                 (sync/files->snapshot {"README.md" "# hi"})))))

(deftest bad-domain-file-is-recorded-as-warning
  (testing "one unparseable file does not nuke the entire import"
    (let [files (sync/snapshot->files sample-snap)
          {:keys [snapshot warnings]}
          (sync/files->snapshot
            (assoc files "domains/books/rules.edn" "[broken edn"))]
      (is (seq warnings) "warning surfaces")
      (is (some #(= "domains/books/rules.edn" (:path %)) warnings))
      (is (seq (:events snapshot)) "other domains still load"))))

(deftest hand-edited-domain-edn-without-timestamps-still-loads
  (testing "domain.edn without :label-at falls back to :at 0 (Open Q #3 default)"
    (let [files {"meta.edn"
                 (str (:rules {:rules {}}) ;; force read; here we just need a literal
                      "{:version 2 :current-domain :hand :selection {:kind :home}\n"
                      " :theme :light :expanded [] :expanded-subs [] :home {}}")
                 "domains/hand/domain.edn"
                 "{:id :hand :label \"Hand-rolled\" :parent nil}"
                 "domains/hand/rules.edn"     "[]"
                 "domains/hand/events.edn"    "[]"
                 "domains/hand/types.edn"     "[]"
                 "domains/hand/predicates.edn" "[]"
                 "domains/hand/queries.edn"   "[]"}
          {:keys [snapshot warnings]} (sync/files->snapshot files)]
      (is (empty? warnings))
      (is (some (fn [e] (= [:hand :domain-label "Hand-rolled"] (:triple e)))
                (:events snapshot))))))

(deftest deleted-domain-disappears-on-next-push
  (testing "Open Question #2 — domain not in snapshot → no files emitted"
    (let [reduced (-> sample-snap
                      ;; Drop everything :books-related
                      (update :rules    #(vec (remove (fn [r] (= :books (:domain r))) %)))
                      (update :events   #(vec (remove (fn [e]
                                                        (or (= :books (-> e :triple first))
                                                            (= :books (-> e :triple peek))))
                                                      %)))
                      (update-in [:schema :predicates]
                                 #(vec (remove (fn [p] (= :books (:domain p))) %))))
          files (sync/snapshot->files reduced)]
      (is (not (contains? files "domains/books/domain.edn")))
      (is (not (contains? files "domains/books/rules.edn")))
      (is (not (contains? files "domains/books/events.edn")))
      ;; people still there
      (is (contains? files "domains/people/domain.edn")))))

(deftest device-id-not-leaked-to-meta-edn
  (let [files (sync/snapshot->files sample-snap)
        text  (get files "meta.edn")]
    (is (not (re-find #":device-id" text))
        "device-id is per-device; must not appear in the synced meta.edn")))

;; ---------------------------------------------------------------------------
;; diff-files

(deftest diff-files-returns-only-changed-paths
  (let [base    {"a.edn" "{:k 1}" "b.edn" "[]" "c.edn" "nil"}
        current {"a.edn" "{:k 2}" "b.edn" "[]" "c.edn" "nil" "d.edn" "true"}]
    (is (= {"a.edn" "{:k 2}" "d.edn" "true"}
           (sync/diff-files base current)))))

(deftest diff-files-no-change-empty
  (let [m {"meta.edn" "x"}]
    (is (= {} (sync/diff-files m m)))))

;; ---------------------------------------------------------------------------
;; unkeywordize-files — guards the parse-json seam.
;;
;; The /snapshot response goes through (js->clj … :keywordize-keys true),
;; which turns the file-path keys ("domains/x/events.edn") into keywords
;; (:domains/x/events.edn — namespace "domains", name "x/events.edn"). All
;; consumers downstream look up paths via STRING keys, so we have to undo
;; that at the seam. Pre-fix, every pull fell into the "first-push" branch
;; and silently preserved local state.

(deftest unkeywordize-files-restores-string-paths
  ;; Path keywords with `/` can't be written as literals (reader chokes on
  ;; double `/`), so build them the same way js->clj does.
  (let [k (fn [s] (keyword s))]
    (testing "single-segment path keyword → string"
      (is (= {"meta.edn" "x"}
             (sync/unkeywordize-files {(k "meta.edn") "x"}))))
    (testing "multi-segment path keyword (namespace + name from js->clj)"
      (is (= {"domains/starter/events.edn" "y"}
             (sync/unkeywordize-files
               {(k "domains/starter/events.edn") "y"}))))
    (testing "nil files map stays nil (first-push branch path)"
      (is (nil? (sync/unkeywordize-files nil))))
    (testing "empty map stays empty"
      (is (= {} (sync/unkeywordize-files {}))))))

(deftest pulled-files-shape-flows-through-empty-or-no-meta-check
  (testing "after unkeywordize, contains? hits the string key the consumer expects"
    (let [k (fn [s] (keyword s))
          pulled (sync/unkeywordize-files
                   {(k "meta.edn") "{:version 2}"
                    (k "domains/starter/events.edn") "[]"})]
      (is (contains? pulled "meta.edn")
          "pre-fix this returned false because the actual key was :meta.edn"))))

;; ---------------------------------------------------------------------------
;; Starter snap determinism — two machines first-running independently must
;; produce byte-identical files.edn output. Pre-fix, every starter event got
;; a fresh random-uuid, so the second machine's first push rewrote events.edn
;; with new ids on every triple (visible in commit ca6eea1 in golova-kb).

(deftest starter-snap-is-deterministic-across-calls
  (let [a (persist/starter-snap)
        b (persist/starter-snap)]
    (testing "event ids are stable"
      (is (= (map :id (:events a)) (map :id (:events b)))))
    (testing "full events vectors compare equal"
      (is (= (:events a) (:events b))))))

(deftest starter-snap-pushes-to-byte-identical-files
  (testing "two independent seeds produce the same files map — no churn on first sync"
    (let [files-a (sync/snapshot->files (persist/starter-snap))
          files-b (sync/snapshot->files (persist/starter-snap))]
      (is (= files-a files-b))
      (is (= (get files-a "domains/starter/events.edn")
             (get files-b "domains/starter/events.edn"))))))

(deftest starter-snap-survives-round-trip-with-no-event-diff
  (testing "seed → push files → pull (files→snapshot) → push again — events.edn byte-identical"
    (let [files-1 (sync/snapshot->files (persist/starter-snap))
          {snap   :snapshot}
          (sync/files->snapshot files-1)
          files-2 (sync/snapshot->files snap)]
      (is (= (get files-1 "domains/starter/events.edn")
             (get files-2 "domains/starter/events.edn"))))))

;; ---------------------------------------------------------------------------
;; Pretty-printer cleanliness — no trailing whitespace on any line.
;; (pprint code-dispatch otherwise leaves ", " on some lines but not others,
;; producing gratuitous diff noise.)

(deftest snapshot-files-have-no-trailing-whitespace
  (let [files (sync/snapshot->files sample-snap)]
    (doseq [[path text] files
            line        (str/split text #"\n")]
      (is (= line (str/trimr line))
          (str "trailing whitespace in " path ": " (pr-str line))))))
