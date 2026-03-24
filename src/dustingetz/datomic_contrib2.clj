(ns dustingetz.datomic-contrib2
  (:import (datomic.query EntityMap)
           (datomic.db Db))
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [dustingetz.data :refer [index-by unqualify realize]]
            [clojure.template :refer [apply-template]]
            [datomic.api :as d] ; onprem only
            #_[hyperfiddle.electric :as e] ; no electric allowed to maximize reuse
            [hyperfiddle.hfql2 :as hfql]
            [hyperfiddle.hfql2.protocols :as hfqlp :refer [Identifiable Suggestable Navigable ComparableRepresentation]]
            [hyperfiddle.rcf :refer [tests % tap]]))

(tests (require '[clojure.datafy :refer [datafy nav]]
         '[dustingetz.mbrainz :refer [test-db lennon pour-lamour yanne cobblestone]])
  (some? @test-db) := true)

(defn easy-attr [db ?a] ; better, copied from datomic-browser.datomic-model
  (when ?a
    (let [!e (d/entity db ?a)]
      [(unqualify (:db/valueType !e))
       (unqualify (:db/cardinality !e))
       (unqualify (:db/unique !e))
       (if (:db/isComponent !e) :component)
       (if (:db/index !e) :indexed)
       (if (:db/fulltext !e) :fulltext)
       ])))

(tests
  (easy-attr @test-db :db/ident) := [:keyword :one :identity nil]
  (easy-attr @test-db :artist/name) := [:string :one nil nil])

(defn entity-attrs
  "Equivalent to `(keys (d/touch entity))`, but faster:
  - Batch lookup, not per-attribute. Do not cause recursion on component values.
  - index-only lookup, leveraging contiguous segments."
  ([entity] (entity-attrs (d/entity-db entity) (:db/id entity)))
  ([db eid]
   (->> (d/datoms db :eavt eid)
     (eduction
       (map :a)
       (distinct)
       (map #(d/ident db %)))
     (into #{}))))

;;; Entity back references

(defn reverse-attr [?kw]
  (if ?kw
    (keyword (namespace ?kw)
      (let [s (name ?kw)]
        (case (.charAt s 0)
          \_ (subs s 1)
          (str "_" s))))))

(tests
  (reverse-attr :foo/bar) := :foo/_bar
  (reverse-attr nil) := nil
  (reverse-attr :foo/_bar) := :foo/bar)

(defn reverse-attribute? [attribute]
  {:pre [(qualified-keyword? attribute)]}
  (clojure.string/starts-with? (name attribute) "_"))

(defn invert-attribute [attribute] ; fixme duplicates reverse-attr
  {:pre [(qualified-keyword? attribute)]}
  (let [nom (name attribute)]
    (keyword (namespace attribute) (if (reverse-attribute? attribute) (subs nom 1) (str "_" nom)))))

(tests
  (reverse-attribute? :foo/bar) := false
  (reverse-attribute? :foo/_bar) := true
  (invert-attribute :abstractRelease/artists) := :abstractRelease/_artists
  (invert-attribute (invert-attribute :abstractRelease/artists)) := :abstractRelease/artists)

(defn find-attr-ident [db attribute-id] ; faster than (:db/ident (d/entity …))
  (:v (first (d/datoms db :eavt attribute-id))))

(defn reverse-refs
  ([db target] (reverse-refs db target false))
  ([db target include-system-refs?]
   (->> (d/datoms db :vaet target)
     (eduction (if include-system-refs? ; single conditional check
                 (map identity) ; noop
                 (remove #(zero? ^long (:e %)))) ; should byte-compile to `==`
       (map (fn [datom] [(find-attr-ident db (:a datom)) (:e datom)]))))))

(comment (reverse-refs @test-db 527765581346058))

(defn back-references [db eid] ; optimized for speed – returns a map {:reverse/_ref #{entity entity ...}
  (as-> (reverse-refs db eid) % ; return eduction of vector pairs
    (dustingetz.data/group-by
      #(get % 0) ; fast `first`
      (fn [coll x] (conj! (or coll (transient #{})) (d/entity db (get x 1)))) ; fast `second`
      %)
    ;; single-pass invert-key + freeze entity sets
    (reduce-kv (fn [r k v] (-> (dissoc! r k) (assoc! (invert-attribute k) (persistent! v))))
      (transient %) %)
    (persistent! %)))

(tests
  (d/touch (d/entity @test-db yanne))
  (def !e (back-references @test-db yanne))
  ; WARNING: these are EntityMaps, NOT maps. d/touch returns native objects!
  #_{:abstractRelease/_artists #{#:db{:id 17592186058336} #:db{:id 17592186067319}},
     :release/_artists #{#:db{:id 17592186069801} #:db{:id 17592186080017}},
     :track/_artists #{#:db{:id 1059929209283807}
                       #:db{:id 862017116284124}
                       #:db{:id 862017116284125}
                       #:db{:id 1059929209283808}}}
  (map type (:abstractRelease/_artists !e)) := [EntityMap datomic.query.EntityMap]
  (map type (:release/_artists !e)) := [datomic.query.EntityMap datomic.query.EntityMap]
  (map type (:track/_artists !e)) := [datomic.query.EntityMap datomic.query.EntityMap datomic.query.EntityMap datomic.query.EntityMap])

(comment
  (back-references _db 778454232478138)
  (back-references _db (:db/id _artist_e))
  )

;;; Datafy/Nav

(let [pull-pattern '[*
                     {:db/valueType [:db/ident]
                      :db/cardinality [:db/ident]
                      :db/unique [:db/ident]}]]
  (defn query-schema
    "Return pulled schema for all attributes, or for one attribute – by :db/ident – if provided."
    ([db]
     (d/q (apply-template ['<pull-pattern>]
            '[:find [(pull ?attr <pull-pattern>) ...]
              :where [:db.part/db :db.install/attribute ?attr]]
            [pull-pattern])
       db
       {:limit -1}))
    ([db a]
     {:pre [(qualified-keyword? a)]}
     (d/q ; can't use d/pull with [:db/ident a], would seem idiomatic, but we must ensure attr is looked up in :db.part/db.
       (apply-template ['<pull-pattern>]
         '[:find (pull ?attr <pull-pattern>) .
           :in $ ?ident
           :where [:db.part/db :db.install/attribute ?attr]
                  [?attr :db/ident ?ident]]
         [pull-pattern])
       db a))))

(defn ref? [indexed-schema a]
  {:pre [(map? indexed-schema)]}
  (= :db.type/ref (get-in indexed-schema [a :db/valueType :db/ident])))

(defn best-human-friendly-identity [!e]
  ; requires application dynamic injection, is this fn a real thing?
  (or #_(best-domain-level-human-friendly-identity entity) (:db/ident !e) (:db/id !e)))

;; ── Datalog d/query analysis ────────────────────────────────────
;; Parse Datalog :find specs, detect identity columns from schema,
;; reshape flat d/query tuples into named maps with navigable links.

(defn- datalog-var?
  "True if x is a Datalog logic variable (symbol starting with ?)."
  [x]
  (and (symbol? x) (str/starts-with? (name x) "?")))

(defn- normalize-datalog-query
  "Normalize a Datalog query (vector or map form) to {:find ... :in ... :where ...}."
  [query]
  (let [q (if (and (map? query) (:query query)) (:query query) query)]
    (if (map? q) q
      ;; Vector form — split at keyword boundaries
      (let [m (->> (partition-by keyword? q)
                   (partition 2)
                   (reduce (fn [m [[k] clauses]] (assoc m k (vec clauses))) {}))]
        (assert (:find m) (str "Datalog query missing :find clause: " (pr-str query)))
        m))))

(defn- datalog-identity-bindings
  "Return the set of :find variables that resolve to entity identities.
   A var is identity if it's in entity position of a [e a v] data pattern,
   or in value position where a is :db.unique/identity in the schema."
  [query unique-identity?]
  (letfn [(find-vars [find-spec]
            ;; Extract logic vars from :find spec. Handles relation, collection,
            ;; tuple, scalar, pull, and aggregate forms.
            (let [elements (if (and (= 1 (count find-spec)) (vector? (first find-spec)))
                             (first find-spec)
                             find-spec)]
              (->> elements
                   (remove #{'... '.})
                   (mapcat (fn [x]
                             (cond
                               (datalog-var? x) [x]
                               (seq? x) (filter datalog-var? x)
                               :else [])))
                   set)))
          (walk-where [clauses]
            ;; Walk :where clauses, collecting vars in entity position or
            ;; unique-identity value position. Recurses into nested clauses.
            (reduce
              (fn [ids clause]
                (cond
                  ;; Data pattern: [e a ...], 2+ elements, first isn't a fn call.
                  (and (vector? clause)
                       (>= (count clause) 2)
                       (not (list? (first clause))))
                  (let [[e a v] clause]
                    (cond-> ids
                      (datalog-var? e) (conj e)
                      (and (some? v)
                           (datalog-var? v)
                           (keyword? a)
                           (unique-identity? a)) (conj v)))
                  ;; Nested clause (not, or, or-join, not-join, etc.): recurse.
                  ;; Rule invocations like (my-rule ?e ?other) also match here —
                  ;; safely, because (filter sequential? (rest clause)) drops the
                  ;; symbol args, so nothing is recursed into for bare rule calls.
                  (sequential? clause)
                  (into ids (walk-where (filter sequential? (rest clause))))
                  :else ids))
              #{}
              clauses))]
    (let [q (normalize-datalog-query query)]
      (set/intersection (find-vars (:find q))
                        (walk-where (:where q))))))

(defn- reshape-datalog-query-result
  "Transform raw d/query result into maps keyed by find-spec variable symbols.
   HFQL's map rendering uses map keys as column headers. Identity columns carry
   hfqlp/-identify metadata so the navigator renders them as navigable links."
  [raw-ret find-spec where-clauses id-keys]
  (letfn [(element->key [element]
            ;; Bare variables stay as symbols. Aggregates keep their list form
            ;; (native EDN, survives URL serialization). Realize lazy seqs so
            ;; they print readably in column headers.
            (cond
              (datalog-var? element) element
              (seq? element) (realize element)
              :else element))
          (find-type [find-spec]
            ;; Classify the :find spec shape for result destructuring.
            (cond
              (and (= 1 (count find-spec)) (vector? (first find-spec)))
              (if (= '... (last (first find-spec))) :collection :tuple)
              (= '. (last find-spec)) :scalar
              :else :relation))
          (var-origins [clauses]
            ;; Map each :find variable to its backing Datomic attribute.
            ;; Only v-position vars get an origin (the attribute they're bound to).
            (reduce
              (fn [origins clause]
                (cond
                  (and (vector? clause)
                       (>= (count clause) 2)
                       (not (list? (first clause))))
                  (let [[_e a v] clause]
                    (cond-> origins
                      (and (some? v) (datalog-var? v) (keyword? a)) (assoc v a)))
                  (sequential? clause)
                  (merge origins (var-origins (filter sequential? (rest clause))))
                  :else origins))
              {}
              clauses))]
    (let [raw-elements (let [elements (if (and (= 1 (count find-spec)) (vector? (first find-spec)))
                                        (first find-spec)
                                        find-spec)]
                         (vec (remove #{'... '.} elements)))
          vars (mapv element->key raw-elements)
          origins (var-origins where-clauses)
          ;; Enrich keys with metadata:
          ;; - ::source-attribute for column header tooltips
          ;; - hfqlp/-identify for identity columns (nav resolves cell values to entities)
          vars (mapv (fn [v elem]
                       (let [inner-var (if (datalog-var? elem)
                                         elem
                                         (first (filter datalog-var? (flatten elem))))
                             attr (get origins inner-var)]
                         (cond-> v
                           attr (vary-meta assoc ::source-attribute attr)
                           (contains? id-keys v)
                           (vary-meta assoc `hfqlp/-identify
                                      ;; Column declares: cell values are entity IDs,
                                      ;; identifiable as (d/entity eid).
                                      (fn [eid] (list `d/entity eid))))))
                     vars raw-elements)
          spec-type (find-type find-spec)]
      (case spec-type
        :collection (mapv #(array-map (first vars) %) raw-ret)
        :relation  (mapv #(zipmap vars %) raw-ret)
        :tuple     [(zipmap vars raw-ret)]
        :scalar    [(array-map (first vars) raw-ret)]))))

(defn- ^:no-doc stringify-query-stats
  "Rewrite :io-stats and :query-stats in a d/query result map,
   stringifying clause forms and binding lists so they render readably.
   TEMPORARY — drop once UI is more ergonomic on query-stats data shape."
  [result-map]
  (letfn [(stringify-val? [k]
            (#{:clause :binds-in :binds-out :preds :unbound-vars} k))
          (stringify-nested-each? [k]
            (#{:sched} k))
          (stringify-sequential-children? [k]
            (#{:query} k))
          (stringify-map [m]
            (when m
              (persistent!
                (reduce-kv
                  (fn [acc k v]
                    (assoc! acc k
                      (cond
                        (stringify-val? k)  (pr-str v)
                        (stringify-nested-each? k) (mapv (fn [group] (mapv pr-str group)) v)
                        (stringify-sequential-children? k)
                        (mapv (fn [x] (if (sequential? x) (pr-str x) x)) v)
                        (map? v)                (stringify-map v)
                        (and (sequential? v)
                             (every? map? v))   (mapv stringify-map v)
                        :else                   v)))
                  (transient {})
                  m))))]
    (cond-> result-map
      (:io-stats result-map)    (update :io-stats stringify-map)
      (:query-stats result-map) (update :query-stats stringify-map))))

(defn- d-query-implicit-db
  "Extract the implicit database from a d/query argument map.
   Convention: the first :args element is bound to `$` (the default db source).
   Returns nil if :args is absent or first element isn't a Db instance."
  [query-map]
  (let [first-arg (first (:args query-map))]
    (when (instance? Db first-arg) first-arg)))

(defn- datalog-query-identity-keys
  "Determine which :find variables resolve to entity identities.
   Requires a database to inspect schema for :db.unique/identity attributes.
   Returns #{} when no db is available (identity detection skipped gracefully)."
  [query-map]
  (if-let [db (d-query-implicit-db query-map)]
    (datalog-identity-bindings (:query query-map)
      (fn [attr] (= :db.unique/identity (.unique (d/attribute db attr)))))
    #{}))

(defn normalize-datalog-query-result
  "Reshape a raw d/query result into named maps with identity metadata.
   Parses the :find spec to derive column names, analyzes :where clauses and
   schema to detect identity columns, preserves io-stats/query-stats as metadata."
  [query-map raw-d-query-result]
  (let [;; d/query returns extended map {:ret ... :io-stats ...} or raw result
        extended?  (map? raw-d-query-result)
        result     (if extended? (stringify-query-stats raw-d-query-result) raw-d-query-result)
        raw-ret    (if extended? (:ret result) result)
        q          (normalize-datalog-query (:query query-map))
        named-ret  (reshape-datalog-query-result raw-ret (:find q) (:where q)
                     (datalog-query-identity-keys query-map))
        residual   (when extended? (dissoc result :ret))]
    ;; Preserve query metadata (io-stats, query-stats) on the collection
    (if (and residual (instance? clojure.lang.IObj named-ret))
      (with-meta named-ret residual)
      named-ret)))

;; ── Protocol extensions ─────────────────────────────────────────

(extend-type datomic.query.EntityMap
  Identifiable
  (-identify [entity] (list `d/entity (best-human-friendly-identity entity)))
  Suggestable
  (-suggest [entity] ; list all attributes of an entity – including reverse refs.
    (let [attributes (cons :db/id (entity-attrs entity))
          reverse-attributes (->> (reverse-refs (d/entity-db entity) (:db/id entity))
                               (map first) (distinct) (map invert-attribute))]
      (hfql/build-hfql (vec (concat attributes reverse-attributes)))))
  Navigable
  (-nav [^datomic.query.EntityMap entity k v]
    (let [[typ card unique? comp?] (easy-attr (.-db entity) k)]
      (cond
        (#{:db/id :db/ident} k) entity ; i.e., hfql links pass as entity
        ; TODO cache schema?
        (and (keyword? v) (ref? (index-by :db/ident (query-schema (.-db entity))) k)) (d/entity (.-db entity) v) ; traverse ident refs
        (= :identity unique?) (if (instance? datomic.query.EntityMap v) v (d/entity (.-db entity) [k v])) ; resolve lookup ref, todo cleanup
        (keyword? k) (k entity v) ; traverse refs or return value
        )))
  ComparableRepresentation
  (-comparable [entity] (str (best-human-friendly-identity entity))))

(comment (d/entity @test-db [:abstractRelease/gid #uuid "320eeca0-a5ff-383f-a66e-b2f559ed0ab6"]))

;; Patch EntityMap printing to differentiate it from regular maps
;; (defonce original-entity-map-print-method (get-method print-method datomic.query.EntityMap))
;; (defmethod print-method datomic.query.EntityMap [e writer]
;;   (.write writer "#datomic.query.EntityMap ")
;;   (binding [*print-namespace-maps* false]
;;     (original-entity-map-print-method e writer)))

#_ ; BAD FUNCTION, leaving this tombstone to warn the next confused person
(defn untouch-refs [indexed-schema touched-entity] ; only touched attrs are present
  (letfn [(untouch-ref [{id :db/id}] (d/entity (.-db touched-entity) id))] ; resolve back to underlying for nav
    (reduce-kv
      (fn [acc k v]
        (if (ref? indexed-schema k)
          (cond
            (set? v) (assoc acc k (set (map untouch-ref v)))
            (map? v) (assoc acc k (untouch-ref v))
            :else (assoc acc k v))
          (assoc acc k v)))
      {} touched-entity)))
#_(tests
  #_(def test-schema (delay (index-by :db/ident (query-schema @test-db))))
  (def !e (d/entity @test-db pour-lamour))
  (:abstractRelease/artists (d/touch !e)) ; := #{#:db{:id 778454232478138} #:db{:id 580542139477874}} -- fail bc entity not= map
  (:abstractRelease/artists (untouch-refs @test-schema (d/touch (d/entity @test-db pour-lamour))))
  (map type *1) := [datomic.query.EntityMap datomic.query.EntityMap])


(tests
  (def !pour-lamour (d/entity @test-db pour-lamour))
  (do (d/touch !pour-lamour) (datafy !pour-lamour))
  ; RCF crash on '_: java.lang.AbstractMethodError: Receiver class datomic.query.EntityMap does not define or inherit an implementation ...
  #_ {:db/id 17592186058336,
      :abstractRelease/gid #uuid "f05a1be3-e383-4cd4-ad2a-150ae118f622",
      :abstractRelease/name "Pour l’amour des sous / Parle au patron, ma tête est malade",
      :abstractRelease/type :release.type/single,
      :abstractRelease/artists _ ; #{datomic.query.EntityMap datomic.query.EntityMap}
      :abstractRelease/artistCredit "Jean Yanne & Michel Magne"}

  "datomic presents deep refs as native entity, NOT maps" ; WARNING: EntityMap prints as {:db/id 1}, which is incredibly confusing.
  (map type (:abstractRelease/artists (d/touch !pour-lamour))) := [datomic.query.EntityMap datomic.query.EntityMap]

  "datafy presents deep refs as native entity NOT maps NOT scalars"
  (map type (:abstractRelease/artists (datafy !pour-lamour))) := [datomic.query.EntityMap datomic.query.EntityMap]

  (tests "self-nav resolves the original underlying reference"
    (let [x (as-> (datafy !pour-lamour) x (nav x :db/id (:db/id x)))]
      (type x) := datomic.query.EntityMap
      (= !pour-lamour x) := true))

  (as-> (datafy !pour-lamour) x
    (nav x :abstractRelease/artists (:abstractRelease/artists x))
    (map type x)) := [datomic.query.EntityMap datomic.query.EntityMap] ; prints as #{#:db{:id 778454232478138} #:db{:id 580542139477874}}

  (comment
    (query-schema _db)
    (def _schema (index-by :db/ident (query-schema _db)))
    (ref? _schema :db/ident)
    (get-in _schema [:abstractRelease/artists])
    (get-in _schema [:artist/country])))

(tests "sanity tests / docs"
  (tests
    (def !yanne
      (as-> (datafy !pour-lamour) x
        (nav x :abstractRelease/artists (:abstractRelease/artists x)) ; entities yanne and magne
        (index-by :db/id x)
        (nav x 778454232478138 (get x 778454232478138))))

    (type !yanne) := datomic.query.EntityMap
    (:db/id !yanne) := yanne)

  (tests
    (datafy !yanne) ; RCF crashes with java.lang.AbstractMethodError, RCF bug?
    #_{:artist/sortName "Yanne, Jean",
       :artist/name "Jean Yanne",
       :artist/type :artist.type/person,
       :artist/country :country/FR,
       :artist/gid #uuid"da0c147b-2da4-4d81-818e-f2aa9be37f9e",
       :artist/startDay 18,
       :artist/endDay 23,
       :artist/startYear 1933,
       :track/_artists _ ; EntityMap -- #{#:db{:id 1059929209283807} #:db{:id 862017116284124} #:db{:id 862017116284125} #:db{:id 1059929209283808}}
       :artist/endMonth 5,
       :release/_artists _ ; EntityMap -- #{#:db{:id 17592186069801} #:db{:id 17592186080017}}
       :abstractRelease/_artists _ ; EntityMap -- #{#:db{:id 17592186058336} #:db{:id 17592186067319}}
       :artist/endYear 2003,
       :db/id 778454232478138,
       :artist/startMonth 7,
       :artist/gender :artist.gender/male}
    (-> (datafy !yanne) :track/_artists first type) := datomic.query.EntityMap
    (-> (datafy !yanne) :track/_artists count) := 4
    (-> (datafy !yanne) :release/_artists count) := 2
    (-> (datafy !yanne) :abstractRelease/_artists count) := 2)

  (tests
    (def !france (as-> (datafy !yanne) x (nav x :artist/country (:artist/country x))))
    (datafy !france)
    #_{:db/id 17592186045645,
       :db/ident :country/FR,
       :country/name "France",
       :release/_country #{#:db{:id 17592186076553} ...},
       :artist/_country #{#:db{:id 765260092944782} ...},
       :label/_country #{#:db{:id 17592186068643} ...}}
    (-> (datafy !france) :release/_country first type) := datomic.query.EntityMap
    (-> (datafy !france) :release/_country count) := 574
    (-> (datafy !france) :artist/_country count) := 140
    (-> (datafy !france) :label/_country count) := 59))

(defn- sql-uri? [^java.net.URI inner-uri]
  (= "sql" (.getScheme inner-uri)))

(defn set-db-name-in-datomic-uri [datomic-uri db-name]
  (let [source-uri (-> datomic-uri (java.net.URI.) (.getSchemeSpecificPart) (java.net.URI.))]
    (if (sql-uri? source-uri)
      ;; sql: db-name is the authority, e.g. datomic:sql://DB_NAME?jdbc:...
      (str "datomic:sql://" db-name "?" (.getQuery source-uri))
      ;; all others: db-name is last path segment
      (let [old-path (.getPath source-uri)
            segments (vec (remove empty? (clojure.string/split old-path #"/")))
            new-segments (if (seq segments)
                           (conj (pop segments) db-name)
                           [db-name])
            new-path (str "/" (clojure.string/join "/" new-segments))]
        (.toString
          (java.net.URI. (str "datomic:" (.getScheme source-uri))
            (.getUserInfo source-uri)
            (.getHost source-uri)
            (.getPort source-uri)
            new-path
            (.getQuery source-uri)
            (.getFragment source-uri)))))))

(defn datomic-uri-db-name [datomic-uri]
  (let [inner-uri (-> datomic-uri (java.net.URI.) (.getSchemeSpecificPart) (java.net.URI.))]
    (if (sql-uri? inner-uri)
      ;; sql: db-name is the authority, e.g. datomic:sql://DB_NAME?jdbc:...
      (.getAuthority inner-uri)
      ;; all others: db-name is last path segment
      (let [segments (remove empty? (clojure.string/split (.getPath inner-uri) #"/"))]
        (last segments)))))

(tests "datomic-uri-db-name extracts database name correctly"
  ; dev protocol URIs
  (datomic-uri-db-name "datomic:dev://localhost:4334/*") := "*"
  (datomic-uri-db-name "datomic:dev://localhost:4334/mydb") := "mydb"

  ; mem protocol URIs
  (datomic-uri-db-name "datomic:mem://test/*") := "*"
  (datomic-uri-db-name "datomic:mem://test/mydb") := "mydb"

  ; ddb protocol URIs
  (datomic-uri-db-name "datomic:ddb://us-east-1/datomic/*") := "*"
  (datomic-uri-db-name "datomic:ddb://us-east-1/datomic/mydb") := "mydb"
  (datomic-uri-db-name "datomic:ddb://eu-west-1/my-table/production") := "production"

  ; sql protocol URIs (db-name is authority, jdbc url is query)
  (datomic-uri-db-name "datomic:sql://*?jdbc:postgresql://localhost:5433/datomic?user=datomic&password=datomic") := "*"
  (datomic-uri-db-name "datomic:sql://mydb?jdbc:postgresql://localhost:5433/datomic?user=datomic&password=datomic") := "mydb"

  ; cass3 protocol URIs
  (datomic-uri-db-name "datomic:cass3://localhost:9042/datomic.datomic/*") := "*"
  (datomic-uri-db-name "datomic:cass3://localhost:9042/datomic.datomic/mydb") := "mydb")

(tests "set-db-name-in-datomic-uri replaces database name correctly"
  ; dev protocol URIs
  (set-db-name-in-datomic-uri "datomic:dev://localhost:4334/*" "mydb")
  := "datomic:dev://localhost:4334/mydb"
  (set-db-name-in-datomic-uri "datomic:dev://localhost:4334/olddb" "newdb")
  := "datomic:dev://localhost:4334/newdb"

  ; mem protocol URIs
  (set-db-name-in-datomic-uri "datomic:mem://test/*" "mydb")
  := "datomic:mem://test/mydb"
  (set-db-name-in-datomic-uri "datomic:mem://test/olddb" "newdb")
  := "datomic:mem://test/newdb"

  ; ddb protocol URIs
  (set-db-name-in-datomic-uri "datomic:ddb://us-east-1/datomic/*" "mydb")
  := "datomic:ddb://us-east-1/datomic/mydb"
  (set-db-name-in-datomic-uri "datomic:ddb://us-east-1/datomic/olddb" "newdb")
  := "datomic:ddb://us-east-1/datomic/newdb"

  ; sql protocol URIs
  (set-db-name-in-datomic-uri "datomic:sql://*?jdbc:postgresql://localhost:5433/datomic?user=datomic&password=datomic" "mydb")
  := "datomic:sql://mydb?jdbc:postgresql://localhost:5433/datomic?user=datomic&password=datomic"

  ; cass3 protocol URIs
  (set-db-name-in-datomic-uri "datomic:cass3://localhost:9042/datomic.datomic/*" "mydb")
  := "datomic:cass3://localhost:9042/datomic.datomic/mydb"

  ; Round-trip tests
  (let [uri "datomic:ddb://us-east-1/datomic/mydb"]
    (set-db-name-in-datomic-uri uri (datomic-uri-db-name uri)))
  := "datomic:ddb://us-east-1/datomic/mydb"

  (let [uri "datomic:sql://mydb?jdbc:postgresql://localhost:5433/datomic?user=datomic&password=datomic"]
    (set-db-name-in-datomic-uri uri (datomic-uri-db-name uri)))
  := "datomic:sql://mydb?jdbc:postgresql://localhost:5433/datomic?user=datomic&password=datomic"

  (let [uri "datomic:cass3://localhost:9042/datomic.datomic/mydb"]
    (set-db-name-in-datomic-uri uri (datomic-uri-db-name uri)))
  := "datomic:cass3://localhost:9042/datomic.datomic/mydb")

(defn serializable-datom-v? [v]
  (or (boolean? v)
    (int? v)
    (instance? java.lang.Long v)
    (float? v)
    (double? v)
    (inst? v)
    (uuid? v)
    (keyword? v)
    (symbol? v)))

(defn encode-datom-value [v]
  (if (serializable-datom-v? v)
    v
    (let [digest (java.security.MessageDigest/getInstance "SHA-256")]
      (cond
        (instance? java.math.BigDecimal v) (do (.update digest (byte-array [(.scale v)]))
                                               (.update digest (.toByteArray (.unscaledValue v))))
        (instance? java.math.BigInteger v) (.update digest (.toByteArray v))
        (bytes? v) (.update digest v)
        (string? v) (.update digest (.getBytes ^String v "UTF-8"))
        (uri? v) (.update digest (.getBytes ^String (.toString v) "UTF-8"))
        :else (.update digest (.getBytes ^String (pr-str v) "UTF-8")))
      (format "%064x" (java.math.BigInteger. 1 (.digest digest))))))

(defn datom-identity
  "Given a Datum instance, return [e a tx added v] where `v` may be hashed to
  SHA-256 if it is not deemed safely serializable. SHA-256 is overblown for our
  use case and may be optimized."
  [{:keys [:e :a :v :tx :added]}]
  [e a (encode-datom-value v) tx added])


(defn resolve-datom
  "Given `[e a serialized-v tx added]`, resolve the corresponding Datum object
  from `db`. `v` is part of a datum's identity. `serialized-v` can be the actual
  datum's `v`, a serialized representation of `v` or a hash of `v`, as produced
  by `encode-datom-value`. In the case where `v` is the actual `v`, then `[e a v
  tx added]` is equivalent to the actual datum, making resolution superfluous.
  Instead and only if needed, call `datomic.db/datum` to construct a proper
  Datum instance.

  Lookup (e, a) in 𝓞(1) through d/history, then find datom in 𝓞(n).
  Can't target tx in 𝓞(1) as v comes before tx in index order.
  Slower than `resolve-datom-via-tx` if number of (e, a)'s historical values
  exceed the average tx size."
  [db e a serialized-v tx added]
  (->>
    (d/datoms (d/history db) :eavt e a)
    (some
      (fn [[_e _a v' tx' added' :as datom]]
        (when-not (.isInterrupted (Thread/currentThread))
          (when (and (= tx tx') (= added added') ; efficient – fails fast
                  (= serialized-v (encode-datom-value v')))
            datom))))))

(defn resolve-datom-via-tx
  "Similar to `resolve-datom` with a different performance trade-off.
  Takes a `conn` and resolve through the datom's transaction.

  Lookup tx in 𝓞(1), then find datom in 𝓞(n).
  Slower than a d/history lookup if tx is abnormally large or if (e, a) pair has
  few historical values."
  [conn e a serialized-v tx added]
  (some->> (d/tx-range (d/log conn) tx (inc tx))
    (:data)
    (some
      (fn [[e' a' v' _tx added' :as datom]]
        (when-not (.isInterrupted (Thread/currentThread))
          (when (and (= e e') (= a a') (= added added') ; efficient – fails fast
                  (= serialized-v (encode-datom-value v')))
            datom))))))
