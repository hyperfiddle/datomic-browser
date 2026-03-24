(ns dustingetz.nav-datomic
  "Pure Clojure layer for Datomic navigation — query functions, protocol
   extensions, resolvers, and sitemap. No Electric dependencies.

   Three-layer architecture:
     datomic_contrib2.clj — stateless fns, EntityMap protocol extensions
     nav_datomic.clj      — dynamic vars, query fns, Datum/Db extensions, resolvers, sitemap
     datomic_browser2.cljc — Electric UI: tooltips, renderers, entrypoints"
  (:require
   [clojure.set :as set]
   [clojure.string :as str]
   [dustingetz.check :refer [check]]
   [dustingetz.data :refer [get-with-residual-meta realize]]
   [datomic.api :as d]
   [datomic.lucene]
   [dustingetz.datomic-contrib2 :as dx] ; side-effect: extends EntityMap
   [dustingetz.str :refer [blank->nil pprint-str]]
   [hyperfiddle.hfql2 :as hfql :refer [hfql hfql-resolve]]
   [hyperfiddle.hfql2.protocols :as hfqlp :refer [Identifiable -hfql-resolve Navigable Suggestable ComparableRepresentation]]
   [hyperfiddle.navigator6.rendering :as rendering]
   [hyperfiddle.navigator6.search :refer [*local-search]])
  (:import [datomic.db Datum Db]))

;; ── Dynamic vars ──────────────────────────────────────────────────
;; Bound via e/*bindings* → HFQL applies with with-bindings* when running resolvers.

(defonce ^:dynamic *uri* nil)                            ; Datomic URI (when browsing by URI)
(defonce ^:dynamic *db-name* nil)                        ; current database name
(defonce ^:dynamic *conn* nil)                           ; Datomic connection
(defonce ^:dynamic *db* nil)                             ; current database value
(defonce ^:dynamic *db-stats* nil)                       ; cached db-stats
(defonce ^:dynamic *filter-predicate* nil)               ; for d/filter
(defonce ^:dynamic *allow-listing-and-browsing-all-dbs* nil) ; security flag for multi-db browsing

;; ── Query functions ───────────────────────────────────────────────

(defn databases
  "List databases. Only meaningful when browsing by Datomic URI."
  []
  (when (some? *uri*)
    (let [database-names-list (cond
                                (= "*" (dx/datomic-uri-db-name *uri*))
                                (d/get-database-names *uri*)

                                *allow-listing-and-browsing-all-dbs*
                                (d/get-database-names (dx/set-db-name-in-datomic-uri *uri* "*"))

                                :else (list *db-name*))]
      (->> database-names-list
        (hfql/navigable (fn [_index db-name] (hfql-resolve `(d/db ~db-name))))))))

(defn attributes
  "Datomic schema, with Datomic query diagnostics. Hack: returns nil when *db* not bound, todo improve exception reporting."
  ([] (attributes (check *db*)))
  ([db]
   (let [x (d/query {:query '[:find [?e ...] :in $ :where [?e :db/valueType]] :args [db]
                      :io-context ::attributes, :query-stats ::attributes})
         x (get-with-residual-meta x :ret)]
     (hfql/navigable (fn [_index ?e] (d/entity db ?e)) x))))

(defn attribute-count [!e]
  (-> *db-stats* :attrs (get (:db/ident !e)) :count))

(defn indexed-attribute? [db ident]
  (true? (:db/index (dx/query-schema db ident))))

(defn fulltext-attribute? [db ident]
  (true? (:db/fulltext (dx/query-schema db ident))))

(defn fulltext-prefix-query [input]
  (some-> input (str) (datomic.lucene/escape-query) (str/replace #"^(\\\*)+" "") (blank->nil) (str "*")))

(defn attribute-detail [!e]
  (let [ident (:db/ident !e)
        search (not-empty (str/trim (str *local-search)))
        fulltext-query (fulltext-prefix-query search)
        entids (cond
                 ;; prefer fulltext search, when available
                 (and (fulltext-attribute? *db* ident) fulltext-query)
                 (d/q '[:find [?e ...] :in $ ?a ?search :where [(fulltext $ ?a ?search) [[?e]]]] *db* ident fulltext-query)
                 ;; indexed prefix search, when available
                 (indexed-attribute? *db* ident)
                 (->> (d/index-range *db* ident search nil)
                   (take-while #(if search (str/starts-with? (str (:v %)) search) true))
                   (map :e))
                 :else ; no available index
                 (->> (d/datoms *db* :aevt ident)
                   (filter #(if search (str/starts-with? (str (:v %)) search) true))
                   (map :e)))]
    (->> entids
      (hfql/filtered)
      (hfql/navigable (fn [_index ?e] (d/entity *db* ?e))))))

(defn summarize-attr [db k]
  (->> (dx/easy-attr db k) (remove nil?) (map name) (str/join " ")))

(defn summarize-attr* [?!a]
  (when ?!a (summarize-attr *db* (:db/ident ?!a))))

(defn tx-detail [!e]
  (mapcat :data (d/tx-range (d/log *conn*) (:db/id !e) (inc (:db/id !e)))))

(def entity-detail identity)
(def attribute-entity-detail identity)

(defn entity-history
  "History datoms for a Datomic entity, both inbound and outbound statements."
  [!e]
  (let [history (d/history *db*)]
    (concat
      (d/datoms history :eavt (:db/id !e !e))
      (d/datoms history :vaet (:db/id !e !e)))))

;; ── d/query analysis & reshaping ────────────────────────────────────
;; Smart wrapper around d/query: parses the :find spec to derive column
;; names, analyzes :where clauses + schema to detect identity columns,
;; reshapes flat tuples into named maps, and preserves query/io stats.

;; -- Stats stringification (TODO: workaround) --
;; The navigator UI interprets vectors/lists as collections and truncates them.
;; These fns rewrite :io-stats/:query-stats so clause forms and binding lists
;; render as readable strings instead of "[?a ...] 3 elements".

(def ^:private stringify-val-keys
  ;; keys whose values are Datomic forms — pr-str the whole value
  #{#_:query :clause :binds-in :binds-out :preds :unbound-vars})

(def ^:private stringify-nested-each-keys
  ;; keys whose values are seqs of clause-groups — pr-str each clause inside each group
  #{:sched})

(def ^:private stringify-sequential-children-keys
  ;; keys where we keep the top-level seq navigable but pr-str any sub-sequences
  ;; e.g. :query — keywords like :find stay, but [?a :attr ?v] and (count ?x) get stringified
  #{:query})

(defn- stringify-datomic-stats-map [m]
  (when m
    (persistent!
      (reduce-kv
        (fn [acc k v]
          (assoc! acc k
            (cond
              (stringify-val-keys k)  (pr-str v)
              ;; sched: list of clause-groups → stringify each clause inside each group
              (stringify-nested-each-keys k) (mapv (fn [group] (mapv pr-str group)) v)
              ;; query: keep top-level vec navigable, stringify only sub-sequences
              (stringify-sequential-children-keys k)
              (mapv (fn [x] (if (sequential? x) (pr-str x) x)) v)
              (map? v)                (stringify-datomic-stats-map v)
              ;; recurse into vectors of maps (e.g. :phases, :clauses)
              (and (sequential? v)
                   (every? map? v))   (mapv stringify-datomic-stats-map v)
              :else                   v)))
        (transient {})
        m))))

(defn- stringify-datomic-stats
  "Rewrite :io-stats and :query-stats in a d/query result map,
   converting clause forms and binding lists to strings so the
   navigator UI doesn't truncate them as collections."
  [result-map]
  (cond-> result-map
    (:io-stats result-map)    (update :io-stats stringify-datomic-stats-map)
    (:query-stats result-map) (update :query-stats stringify-datomic-stats-map)))

;; -- Datalog identity analysis --
;; Given a Datomic datalog query and schema info, determine which :find
;; variables resolve to entity identities — so the navigator can render
;; them as navigable links instead of plain scalars.

(defn- logic-var? [x]
  (and (symbol? x) (str/starts-with? (name x) "?")))

(defn- normalize-query
  "Normalize vector-form or d/query-map-form queries to a map
   with :find, :in, :where keys."
  [query]
  ;; Unwrap d/query map form: {:query [...] :args [...]}
  (let [q (if (and (map? query) (:query query)) (:query query) query)]
    (if (map? q) q
      ;; Vector form — split at keyword boundaries
      (->> (partition-by keyword? q)
           (partition 2)
           (reduce (fn [m [[k] clauses]] (assoc m k (vec clauses))) {})))))

(defn- find-spec-vars
  "Extract logic vars selected in the :find spec.
   Handles relation, collection, tuple, scalar, pull, and aggregate forms."
  [find-spec]
  ;; Unwrap collection/tuple: [[?e ...]] or [[?e ?name]] → inner vector
  (let [elements (if (and (= 1 (count find-spec)) (vector? (first find-spec)))
                   (first find-spec)
                   find-spec)]
    (->> elements
         (remove #{'... '.})           ; strip find-spec markers
         (mapcat (fn [x]
                   (cond
                     (logic-var? x) [x]
                     ;; pull/aggregate: (pull ?e [...]) or (count ?e)
                     (seq? x) (filter logic-var? x)
                     :else [])))
         set)))

(defn- walk-where-clauses
  "Walk :where clauses, collecting vars that are entity identities.
   Open walker — recurses into any sequential sub-structure,
   doesn't hardcode clause type names."
  [clauses unique-identity?]
  (reduce
    (fn [ids clause]
      (cond
        ;; Data pattern: [e a ...], 2+ elements, first isn't a fn call.
        ;; Datomic accepts [e a] (implicit v), [e a v], [e a v tx], [e a v tx added].
        (and (vector? clause)
             (>= (count clause) 2)
             (not (list? (first clause))))
        (let [[e a v] clause]
          (cond-> ids
            (logic-var? e) (conj e)
            (and (some? v) ; v absent in 2-element patterns
                 (logic-var? v)
                 (keyword? a)
                 (unique-identity? a)) (conj v)))

        ;; Nested clause (not, or, rules, etc.): recurse into sequential children
        (sequential? clause)
        (into ids (walk-where-clauses
                    (filter sequential? (rest clause))
                    unique-identity?))

        :else ids))
    #{}
    clauses))

(defn- db->unique-identity?
  "Build a unique-identity? predicate from a Datomic db.
   Uses .unique on AttrInfo (d/attribute returns AttrInfo, not a map)."
  [db]
  (fn [attr] (= :db.unique/identity (.unique (d/attribute db attr)))))

(defn- identity-bindings
  "Given a Datomic datalog query and a unique-identity? predicate,
   return the set of :find logic variables that resolve to entity identities.
   A var is identity if it's in entity position of a [e a v] data pattern,
   or in value position where a is :db.unique/identity in the schema."
  [query unique-identity?]
  (let [q (normalize-query query)
        find-vars (find-spec-vars (:find q))
        where-ids (walk-where-clauses (:where q) unique-identity?)]
    (set/intersection find-vars where-ids)))

;; -- Var provenance --
;; Map each find-spec variable to the Datomic attribute it's bound to
;; in the :where clauses. Used for column header tooltips.

(defn- var-origins
  "Walk :where clauses, return {var attr} mapping each variable to its
   backing Datomic attribute. e-position vars map to :entity."
  [clauses]
  (reduce
    (fn [origins clause]
      (cond
        ;; Data pattern: [e a v ...]
        ;; Only v-position vars get an origin (the attribute they're bound to).
        ;; e-position vars don't have a meaningful single attribute origin.
        (and (vector? clause)
             (>= (count clause) 2)
             (not (list? (first clause))))
        (let [[_e a v] clause]
          (cond-> origins
            (and (some? v) (logic-var? v) (keyword? a)) (assoc v a)))

        ;; Recurse into nested clauses
        (sequential? clause)
        (merge origins (var-origins (filter sequential? (rest clause))))

        :else origins))
    {}
    clauses))

;; -- Result reshaping --
;; Transform raw d/query results into maps keyed by find-spec variable
;; symbols, so HFQL materializes variable names as column headers.

(defn- find-element->key
  "Convert a find-spec element to a map key.
   Bare variables stay as symbols. Aggregates like (count ?release) keep
   their list form — lists are native EDN and survive URL serialization.
   (symbol (pr-str ...)) produced symbols whose names looked like lists,
   breaking EDN roundtrip: the reader parsed them back as lists, not symbols."
  [element]
  (cond
    (logic-var? element) element
    ;; Aggregate or pull: keep the list form, realized to a proper list.
    ;; Lazy seqs print as opaque `LazySeq@hash` in column headers;
    ;; `realize` produces a plain list that prints and serializes correctly.
    (seq? element) (realize element)
    :else element))

(defn- find-spec-keys-ordered
  "Return an ordered vector of map keys for the find-spec elements.
   Bare vars → symbols, aggregates → symbols named after the expression."
  [find-spec]
  (let [elements (if (and (= 1 (count find-spec)) (vector? (first find-spec)))
                   (first find-spec)
                   find-spec)]
    (->> elements
         (remove #{'... '.})
         (mapv find-element->key))))

(defn- find-spec-type
  "Classify the :find spec shape. Determines how to destructure the raw result."
  [find-spec]
  (cond
    ;; [[?e ...]] → collection, [[?e ?name]] → tuple
    (and (= 1 (count find-spec)) (vector? (first find-spec)))
    (if (= '... (last (first find-spec))) :collection :tuple)
    ;; ?e . → scalar
    (= '. (last find-spec)) :scalar
    ;; ?e ?name → relation (default)
    :else :relation))

(defn- reshape-datomic-result
  "Transform raw Datomic result to vector of maps keyed by find-spec variable symbols.
   HFQL's map rendering (hfql2.cljc:1144) uses map keys as column headers.
   Each symbol key carries :origin metadata (backing Datomic attribute) for tooltips.
   Identity columns (from id-keys) carry hfqlp/-identify metadata — the column key
   declares how to produce a symbolic identity form from cell values."
  [raw-ret find-spec where-clauses id-keys]
  (let [;; Raw find elements before key conversion — needed to resolve inner vars for aggregates
        raw-elements (let [elements (if (and (= 1 (count find-spec)) (vector? (first find-spec)))
                                      (first find-spec)
                                      find-spec)]
                       (vec (remove #{'... '.} elements)))
        vars (mapv find-element->key raw-elements)
        origins (var-origins where-clauses)
        ;; Enrich symbols with metadata:
        ;; - ::source-attribute for column header tooltips
        ;; - hfqlp/-identify for identity columns (teaches nav how to resolve cell values to entities)
        vars (mapv (fn [v elem]
                     (let [inner-var (if (logic-var? elem)
                                       elem
                                       (first (filter logic-var? (flatten elem))))
                           attr (get origins inner-var)]
                       (cond-> v
                         attr            (vary-meta assoc ::source-attribute attr)
                         (contains? id-keys v)
                         (vary-meta assoc `hfqlp/-identify
                                    ;; Column declares: cell values are entity IDs,
                                    ;; identifiable as (d/entity eid).
                                    (fn [eid] (list `d/entity eid))))))
                   vars raw-elements)
        spec-type (find-spec-type find-spec)]
    (case spec-type
      :collection (mapv #(array-map (first vars) %) raw-ret)
      :relation  (mapv #(zipmap vars %) raw-ret)
      :tuple     [(zipmap vars raw-ret)]
      :scalar    [(array-map (first vars) raw-ret)])))

(defn d-query
  "Smart d/query wrapper: named columns, identity detection, stats preservation.
   Parses the query's :find spec to derive column names, analyzes :where clauses
   and schema to detect identity columns, reshapes flat tuples into named maps."
  [query-map]
  (let [raw-result (d/query query-map)
        ;; d/query returns extended map {:ret ... :io-stats ...} or raw result
        ;; depending on Datomic version and query form. Normalize both.
        extended?  (map? raw-result)
        result     (if extended? (stringify-datomic-stats raw-result) raw-result)
        raw-ret    (if extended? (:ret result) result)
        query      (:query query-map)
        q          (normalize-query query)
        db         *db*
        id-keys    (when db (identity-bindings query (db->unique-identity? db)))
        named-ret  (reshape-datomic-result raw-ret (:find q) (:where q) (or id-keys #{}))
        residual   (when extended? (dissoc result :ret))]
    ;; Preserve query metadata (io-stats, query-stats) on the collection
    (if (and residual (instance? clojure.lang.IObj named-ret))
      (with-meta named-ret residual)
      named-ret)))

;; ── Protocol extensions ───────────────────────────────────────────

(defn- entity-exists? [db eid]
  (and (some? eid) (seq (d/datoms db :eavt eid))))

(extend-type Datum
  Identifiable
  (-identify [datum] `(datomic.db/datum ~@(dx/datom-identity datum)))
  Navigable
  (-nav [[e a v tx added] k _]
    (case k
      :e (d/entity *db* e)
      :a (d/entity *db* a)
      :v (if (= :db.type/ref (:value-type (d/attribute *db* a))) (d/entity *db* v) v)
      :tx (d/entity *db* tx)
      :added added))
  Suggestable
  (-suggest [_] (hfql [:e :a :v :tx :added]))
  ComparableRepresentation
  (-comparable [datum] (into [] datum)))

(defn db-name [db] (::db-name (meta db)))

(extend-type Db
  Identifiable
  (-identify [db] (when-let [nm (db-name db)]
                    (let [id `(d/db ~nm)
                          id (if (d/is-history db) `(d/history ~id) id)
                          id (if (d/is-filtered db) `(d/filter ~id) id)
                          id (if (d/since-t db) `(d/since ~id ~(d/since-t db)) id)
                          id (if (d/as-of-t db) `(d/as-of ~id ~(d/as-of-t db)) id)]
                      id)))
  ComparableRepresentation
  (-comparable [db] (db-name db)))

;; ── Resolvers ─────────────────────────────────────────────────────

(defmethod -hfql-resolve `d/entity [[_ eid]]
  (when (entity-exists? *db* eid)
    (d/entity *db* eid)))

(defmethod -hfql-resolve `datomic.db/datum [[_ e a serialized-v tx added]]
  (dx/resolve-datom *db* e a serialized-v tx added))

(defmethod -hfql-resolve `d/db [[_ db-name]]
  (when *uri*
    (let [datomic-uri-db-name (dx/datomic-uri-db-name *uri*)]
      (when (or (= "*" datomic-uri-db-name)
              (= db-name datomic-uri-db-name)
              *allow-listing-and-browsing-all-dbs*)
        (with-meta (d/db (d/connect (dx/set-db-name-in-datomic-uri *uri* db-name)))
          {::db-name db-name})))))

(defmethod -hfql-resolve `d/history [[_ db]] (d/history (hfql-resolve db)))
(defmethod -hfql-resolve `d/filter  [[_ db]] (d/filter (hfql-resolve db) *filter-predicate*))
(defmethod -hfql-resolve `d/since [[_ db t]] (d/since (hfql-resolve db) t))
(defmethod -hfql-resolve `d/as-of [[_ db t]] (d/as-of (hfql-resolve db) t))

;; ── Sitemap ──────────────────────────────────────────────────────
;; Routes with ::hfql/Tooltip metadata pointing to FnTooltip in
;; hyperfiddle.navigator6.rendering. Agents bind *tooltip-fn*
;; in e/*bindings* to provide the tooltip implementation.
;; TODO: ColumnHeaderTooltip and Render need factory syntax in HFQL analyzer
;; to avoid brittle global bindings — backlogged.

(def sitemap
  {'databases
   (hfql {(databases) {* [^{::hfql/link ['. [`(~'Inject ~'%v) 'attributes]]}
                           db-name d/db-stats]}})

   'attributes
   (hfql {(attributes)
          {* ^{::hfql/select '(attribute-entity-detail %)}
           [^{::hfql/link '(attribute-detail %)
              ::hfql/Tooltip `hyperfiddle.navigator6.rendering/FnTooltip}
            #(:db/ident %)
            attribute-count
            summarize-attr*]}})

   'attribute-entity-detail
   (hfql {attribute-entity-detail ^{::hfql/Tooltip `hyperfiddle.navigator6.rendering/FnTooltip}
          [#(:db/id %)
           attribute-count
           summarize-attr*
           *]})

   'attribute-detail
   (hfql {attribute-detail
          {* ^{::hfql/Tooltip `hyperfiddle.navigator6.rendering/FnTooltip}
           [^{::hfql/link '(entity-detail %)}
            #(:db/id %)]}})

   'tx-detail
   (hfql {tx-detail
          {* [^{::hfql/link '(entity-detail :e)
                ::hfql/Tooltip `hyperfiddle.navigator6.rendering/FnTooltip}
              #(:e %)
              ^{::hfql/link '(attribute-detail :a)
                ::hfql/Tooltip `hyperfiddle.navigator6.rendering/FnTooltip}
              ^{::hfql/label :db/ident}
              {:a :db/ident}
              :v]}})

   'entity-detail
   (hfql {entity-detail ^{::hfql/Tooltip `hyperfiddle.navigator6.rendering/FnTooltip}
          [#(:db/id %)
           *]})

   'entity-history
   (hfql {entity-history
          {* [^{::hfql/link '(entity-detail :e)
                ::hfql/Tooltip `hyperfiddle.navigator6.rendering/FnTooltip}
              #(:e %)
              ^{::hfql/link '(attribute-detail :a)
                ::hfql/Tooltip `hyperfiddle.navigator6.rendering/FnTooltip}
              {:a :db/ident}
              :v
              ^{::hfql/link '(tx-detail %v)
                ::hfql/Tooltip `hyperfiddle.navigator6.rendering/FnTooltip}
              #(:tx %)
              :added]}})})

;; ── Setup function (DI for agent and fiddle hosting) ────────────

(defn make-setup-fn
  "Create the multi-arity setup-fn for dependency injection.
   Zero-arity:  static bindings for root (URI, security flag, rendering config).
   One-arity:   called per Inject route segment with db-name from URL."
  [datomic-uri]
  (fn
    ([] ;; Static bindings — always active on every page
     {#'*uri* datomic-uri
      #'*allow-listing-and-browsing-all-dbs* true
      #'rendering/*server-pretty
      {datomic.query.EntityMap
       (fn [entity] (str "EntityMap[" (dx/best-human-friendly-identity entity) "]"))}
      #'rendering/*tooltip-fn*
      (fn [_entity _edge value]
        (when (instance? datomic.Entity value)
          (pprint-str (into {} (d/touch value)) :print-length 10 :print-level 2)))})
    ([db-name] ;; Called when route has (Inject "mbrainz-full")
     (let [uri (dx/set-db-name-in-datomic-uri datomic-uri db-name)
           conn (d/connect uri)
           db (d/db conn)]
       {#'*uri*      uri
        #'*db-name*  db-name
        #'*conn*     conn
        #'*db*       db
        #'*db-stats* (d/db-stats db)}))))

