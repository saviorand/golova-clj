(ns golova.state.sources-test
  "Pure tests for the mapping DSL + the snapshot↔files round-trip of
  sources.edn. Doesn't exercise refresh-source! end-to-end (that needs
  fetch + rebuild, which is a browser smoke test)."
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is testing]]
            [golova.state.sources :as src]
            [golova.state.sync :as sync]))

;; ---------------------------------------------------------------------------
;; path-lookup — walks nested maps/vectors via string keys, supports
;; numeric indices into vectors, tolerates keyword OR string keys.

(deftest path-lookup-walks-flat-map
  (is (= "Alice" (src/path-lookup {:name "Alice"} ["name"])))
  (is (= 42     (src/path-lookup {"age" 42}      ["age"]))))

(deftest path-lookup-walks-nested-map
  (let [r {:user {:profile {:name "Alice"}}}]
    (is (= "Alice" (src/path-lookup r ["user" "profile" "name"])))))

(deftest path-lookup-walks-vector-by-index
  (let [r {:tags ["a" "b" "c"]}]
    (is (= "b" (src/path-lookup r ["tags" "1"])))))

(deftest path-lookup-nil-on-missing
  (is (nil? (src/path-lookup {} ["nope"])))
  (is (nil? (src/path-lookup {:x {:y 1}} ["x" "missing"]))))

;; ---------------------------------------------------------------------------
;; template-path — distinguishes literal keywords from template paths.

(deftest template-path-recognises-prefixed-keywords
  (is (= ["foo"]        (src/template-path :item :item.foo)))
  (is (= ["foo" "bar"]  (src/template-path :item :item.foo.bar))))

(deftest template-path-returns-nil-for-non-templates
  (is (nil? (src/template-path :item :item)))
  (is (nil? (src/template-path :item :other.foo)))
  (is (nil? (src/template-path :item "literal-string"))))

;; ---------------------------------------------------------------------------
;; to-ident — coerce a looked-up value into an entity-ident keyword.

(deftest to-ident-keyword-passthrough
  (is (= :foo (src/to-ident :foo))))

(deftest to-ident-slugifies-strings
  (is (= :alice-smith    (src/to-ident "Alice Smith")))
  (is (= :alice          (src/to-ident "alice"))))

;; ---------------------------------------------------------------------------
;; record->triples — the meat of the mapping DSL.

(def ^:private sample-mapping
  {:each-as :item
   :id-from :item.full_name
   :triples [[:item :kind  :repo]                  ; static value
             [:item :name  :item.name]              ; path lookup
             [:item :stars :item.stargazers_count]
             [:item :lang  :item.language]]})

(def ^:private sample-record
  {:full_name "saviorand/golova-clj"
   :name "golova-clj"
   :stargazers_count 5
   :language "Clojure"})

(deftest record-to-triples-resolves-template-vars-and-paths
  (let [tps (src/record->triples sample-mapping sample-record)]
    (is (= 4 (count tps)))
    (is (every? #(= :saviorand-golova-clj (first %)) tps)
        "entity ident derived from :id-from path and slugified")
    (is (some #(= [:saviorand-golova-clj :kind  :repo]     %) tps))
    (is (some #(= [:saviorand-golova-clj :name  "golova-clj"] %) tps))
    (is (some #(= [:saviorand-golova-clj :stars 5]            %) tps))
    (is (some #(= [:saviorand-golova-clj :lang  "Clojure"]    %) tps))))

(deftest record-to-triples-drops-triples-with-missing-values
  (let [record-without-lang (dissoc sample-record :language)
        tps (src/record->triples sample-mapping record-without-lang)]
    (is (not-any? #(= :lang (second %)) tps)
        "triple whose RHS resolved to nil should be omitted")
    (is (= 3 (count tps)))))

(deftest record-to-triples-handles-nested-paths
  (let [mapping {:each-as :p
                 :id-from :p.id
                 :triples [[:p :title :p.properties.title.0.text]]}
        record  {:id "abc"
                 :properties {:title [{:text "Hello world"}]}}]
    (is (= [[:abc :title "Hello world"]]
           (src/record->triples mapping record)))))

(deftest records-to-triples-flattens-batch
  (let [rs [{:full_name "a/b" :name "b" :stargazers_count 1 :language "Go"}
            {:full_name "c/d" :name "d" :stargazers_count 2 :language "Rust"}]
        tps (src/records->triples sample-mapping rs)]
    (is (= 8 (count tps)))
    (is (= 2 (count (distinct (map first tps)))))))

;; ---------------------------------------------------------------------------
;; Deterministic event ids — re-asserting the same fact produces the same id.

(deftest deterministic-source-event-id-is-stable
  (is (= (src/deterministic-source-event-id "starred" :repo-x :stars 5)
         (src/deterministic-source-event-id "starred" :repo-x :stars 5))
      "same source + triple → same id")
  (is (not= (src/deterministic-source-event-id "starred" :repo-x :stars 5)
            (src/deterministic-source-event-id "starred" :repo-x :stars 6))
      "different value → different id")
  (is (not= (src/deterministic-source-event-id "starred" :repo-x :stars 5)
            (src/deterministic-source-event-id "other"   :repo-x :stars 5))
      "different source → different id (so two sources don't collide)"))

(deftest triples-to-events-tags-with-source-prefix
  (let [events (src/triples->events "starred-repos"
                                     [[:r1 :stars 5]
                                      [:r1 :name "r1"]])]
    (is (every? #(= "src/starred-repos" (:source %)) events))
    (is (every? #(= :assert (:op %)) events))
    (is (every? #(= 0 (:at %)) events))
    (is (every? #(str/starts-with? (:id %) "src-") events))))

;; ---------------------------------------------------------------------------
;; Snapshot round-trip — sources.edn appears, parses back, no churn.

(def ^:private snap-with-sources
  {:version 2
   :current-domain :people
   :selection {:kind :home}
   :theme :light
   :expanded #{:people}
   :expanded-subs #{}
   :home {:onboarding-collapsed? true}
   :rules []
   :events []
   :schema {:types      []
            :predicates []
            :queries    []
            :sources    [{:name "starred-repos"
                          :kind :http-json
                          :url  "https://api.github.com/users/me/starred"
                          :mapping {:each-as :item
                                    :id-from :item.full_name
                                    :triples [[:item :kind :repo]
                                              [:item :name :item.name]]}
                          :domain :people}]}})

(deftest sources-edn-is-emitted-per-domain
  (let [files (sync/snapshot->files snap-with-sources)]
    (is (contains? files "domains/people/sources.edn"))
    (is (re-find #":kind :http-json" (get files "domains/people/sources.edn")))))

(deftest sources-edn-round-trips
  (let [files (sync/snapshot->files snap-with-sources)
        {:keys [snapshot warnings]} (sync/files->snapshot files)
        srcs   (get-in snapshot [:schema :sources])]
    (is (empty? warnings))
    (is (= 1 (count srcs)))
    (is (= "starred-repos" (-> srcs first :name)))
    (is (= :http-json      (-> srcs first :kind)))
    (is (= :people         (-> srcs first :domain)))
    (is (= :item.full_name (-> srcs first :mapping :id-from)))))

(deftest sources-edn-is-byte-stable-on-second-round-trip
  (let [files-1 (sync/snapshot->files snap-with-sources)
        {snap2 :snapshot} (sync/files->snapshot files-1)
        files-2 (sync/snapshot->files snap2)]
    (is (= (get files-1 "domains/people/sources.edn")
           (get files-2 "domains/people/sources.edn")))))

;; ---------------------------------------------------------------------------
;; Domain assignment for sources — strips :domain from per-file form,
;; reattaches on import (same pattern as queries / predicates).

(deftest sources-edn-strips-domain-key
  (let [files (sync/snapshot->files snap-with-sources)
        text  (get files "domains/people/sources.edn")]
    (is (not (re-find #":domain " text))
        ":domain belongs to the file path, not the record")))
