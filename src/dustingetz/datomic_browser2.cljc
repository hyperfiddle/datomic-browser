(ns dustingetz.datomic-browser2
  (:require [contrib.data :refer [get-with-residual-meta]]
            [hyperfiddle.electric3 :as e]
            ;; [hyperfiddle.hfql0 #?(:clj :as :cljs :as-alias) hfql]
            [hyperfiddle.hfql2 :as hfql :refer [hfql]]
            [hyperfiddle.hfql2.protocols :refer [Identifiable hfql-resolve Navigable Suggestable ComparableRepresentation]]
            [hyperfiddle.navigator6 :as navigator :refer [Navigate]]
            [hyperfiddle.navigator6.search :refer [*local-search]]
            [hyperfiddle.router5 :as r]
            [hyperfiddle.electric-dom3 :as dom]
            [hyperfiddle.electric-forms5 :refer [Checkbox*]]
            [dustingetz.loader :refer [Loader]]
            [dustingetz.str :refer [pprint-str blank->nil #?(:cljs format-number-human-friendly)]]
            [clojure.string :as str]
            #?(:clj [datomic.api :as d])
            #?(:clj [datomic.lucene])
            #?(:clj [dustingetz.datomic-contrib2 :as dx])))

(e/declare ^:dynamic *uri*) ; current Datomic URI. Available when injected. Not available when browsing a Datomic connection.
(e/declare ^:dynamic *db-name*) ; current Datomic database name. Available when *uri* is available.
(e/declare ^:dynamic *conn*) ; current Datomic connection. Always available. Either injected by browsing a connection object or derived from *uri* when browsing by URI.
(e/declare ^:dynamic *db*) ; current Datomic database reference. Always available.
(e/declare ^:dynamic *db-stats*) ; shared for perfs – safe to compute only once per *db* value.
(e/declare ^:dynamic *filter-predicate*) ; for injecting predicates for d/filter
(e/declare ^:dynamic *allow-listing-and-browsing-all-dbs*) ; when browsing by Datomic URI, allow listing and browsing other databases than the currently selected one. Default to false (disallowed).

#?(:clj (defn databases [] ; only meaningful when browsing by Datomic URI. Browsing a Datomic connection object doesn't allow listing databases.
          (when (some? *uri*)
            (let [database-names-list (cond
                                        (= "*" (dx/datomic-uri-db-name *uri*)) ; Injected *uri* is a wildcard Datomic URI, allowing database listing.
                                        (d/get-database-names *uri*)

                                        *allow-listing-and-browsing-all-dbs* ; defaults to false – must be explicitly allowed at the entrypoint.
                                        (d/get-database-names (dx/set-db-name-in-datomic-uri *uri* "*"))

                                        :else (list *db-name*))] ; only list current db.
              (->> database-names-list
                (hfql/navigable (fn [_index db-name] (hfql-resolve `(d/db ~db-name)))))))))

#?(:clj (defn attributes "Datomic schema, with Datomic query diagnostics"
          []
          (let [x (d/query {:query '[:find [?e ...] :in $ :where [?e :db/valueType]] :args [*db*]
                            :io-context ::attributes, :query-stats ::attributes})
                x (get-with-residual-meta x :ret)]
            (hfql/navigable (fn [_index ?e] (d/entity *db* ?e)) x))))

#?(:clj (defn attribute-count "hello"
          [!e] (-> *db-stats* :attrs (get (:db/ident !e)) :count)))

#?(:clj (defn indexed-attribute? [db ident] (true? (:db/index (dx/query-schema db ident)))))
#?(:clj (defn fulltext-attribute? [db ident] (true? (:db/fulltext (dx/query-schema db ident)))))
#?(:clj (defn fulltext-prefix-query [input] (some-> input (str) (datomic.lucene/escape-query) (str/replace #"^(\\\*)+" "") (blank->nil) (str "*"))))

#?(:clj (defn attribute-detail [!e]
          (let [ident (:db/ident !e)
                search (not-empty (str/trim (str *local-search))) ; capture dynamic for lazy take-while/filter
                fulltext-query (fulltext-prefix-query search)
                entids (cond
                         ;; prefer fulltext search, when available
                         (and (fulltext-attribute? *db* ident) fulltext-query)
                         (d/q '[:find [?e ...] :in $ ?a ?search :where [(fulltext $ ?a ?search) [[?e]]]] *db* ident fulltext-query)
                         ;; indexed prefix search, when available
                         (indexed-attribute? *db* ident)
                         (->> (d/index-range *db* ident search nil) ; end is exclusive, can't pass *search twice
                           (take-while #(if search (str/starts-with? (str (:v %)) search) true))
                           (map :e))
                         :else ; no available index
                         (->> (d/datoms *db* :aevt ident) ; e.g. :language/name is a string but neither indexed nor fulltext
                           (filter #(if search (str/starts-with? (str (:v %)) search) true)) ; can't use take-while – datoms are not ordered by v
                           (map :e)))]
            (->> entids
              (hfql/filtered) ; optimisation – tag as already filtered, disable auto in-memory search
              (hfql/navigable (fn [_index ?e] (d/entity *db* ?e)))))))

#?(:clj (defn summarize-attr [db k] (->> (dx/easy-attr db k) (remove nil?) (map name) (str/join " "))))
#?(:clj (defn summarize-attr* [?!a] (when ?!a (summarize-attr *db* (:db/ident ?!a)))))

#?(:clj (defn tx-detail [!e] (mapcat :data (d/tx-range (d/log *conn*) (:db/id !e) (inc (:db/id !e))))))

#?(:clj (def entity-detail identity))
#?(:clj (def attribute-entity-detail identity))

#?(:clj (defn entity-history
          "history datoms in connection with a Datomic entity, both inbound and outbound statements."
          [!e]
          (let [history (d/history *db*)]
            (concat
              (d/datoms history :eavt (:db/id !e !e))
              (d/datoms history :vaet (:db/id !e !e)) ; reverse index
              ))))

(e/defn ^::e/export EntityTooltip [entity edge value] ; FIXME edge is a custom hyperfiddle type
  (e/server (pprint-str (into {} (d/touch value)) :print-length 10 :print-level 2))) ; force conversion to map for pprint to wrap lines

(e/defn ^::e/export SemanticTooltip [entity edge value] ; FIXME edge is a custom hyperfiddle type
  (e/server
    (let [attribute (hfql/symbolic-edge edge)]
      (e/Reconcile
        (cond (= :db/id attribute) (EntityTooltip entity edge value)
              (qualified-keyword? value)
              (let [[typ _ unique?] (dx/easy-attr *db* attribute)]
                (e/Reconcile
                  (cond
                    (= :db/id attribute) (EntityTooltip entity edge value)
                    (= :ref typ) (pprint-str (d/pull *db* ['*] value) :print-length 10 :print-level 2)
                    (= :identity unique?) (pprint-str (d/pull *db* ['*] [attribute #_(:db/ident (d/entity db a)) value]) ; resolve lookup ref
                                            :print-length 10 :print-level 2)
                    () nil))))))))

(e/defn ^::e/export SummarizeDatomicAttribute [_entity edge _value] ; FIXME props is a custom hyperfiddle type
  (e/server
    ((fn [] ; IIFE for try/catch support – Electric 3 doesn't have try/catch yet.
       (try (str/trim (str (hfql/describe-formatted edge) " " (summarize-attr *db* (hfql/symbolic-edge edge))))
            (catch Throwable _))))))

(e/defn ^::e/export EntityDbidCell [entity edge value] ; FIXME edge is a custom hyperfiddle type
  (dom/span (dom/text (e/server (hfql/identify value)) " ") (r/link ['. [`(~'entity-history ~(hfql/identify entity))]] (dom/text "entity history"))))

(e/defn ^::e/export HumanFriendlyAttributeCount [entity edge value]
  (e/client (dom/span (dom/props {:title (format-number-human-friendly value)})
              (dom/text (format-number-human-friendly value :notation :compact :maximumFractionDigits 1)))))

#?(:clj (defn- entity-exists? [db eid] (and (some? eid) (seq (d/datoms db :eavt eid))))) ; d/entity always return an EntityMap, even for a non-existing :db/id
#?(:clj (defmethod hfql-resolve `d/entity [[_ eid]] (when (entity-exists? *db* eid) (d/entity *db* eid))))

#?(:clj (defn- best-human-friendly-identity [entity] (or #_(best-domain-level-human-friendly-identity entity) (:db/ident entity) (:db/id entity))))

#?(:clj ; list all attributes of an entity – including reverse refs.
   (extend-type datomic.query.EntityMap
     Identifiable
     (identify [entity] (list `d/entity (best-human-friendly-identity entity)))
     Suggestable
     (suggest [entity]
       (let [attributes (cons :db/id (dx/entity-attrs entity))
             reverse-attributes (->> (dx/reverse-refs (d/entity-db entity) (:db/id entity)) (map first) (distinct) (map dx/invert-attribute))]
         (hfql/build-hfql (vec (concat attributes reverse-attributes)))))
     ComparableRepresentation
     (comparable [entity] (str (best-human-friendly-identity entity))))) ; Entities are not comparable, but their printable representation (e.g. :db/ident) is.

#?(:clj ; list all attributes of an entity – including reverse refs.
   (extend-type datomic.db.Datum
     Identifiable
     (identify [datum] `(datomic.db/datum ~@(dx/datom-identity datum)))
     Navigable
     (nav [[e a v tx added] k _]
       (case k
         :e (d/entity *db* e)
         :a (d/entity *db* a)
         :v (if (= :db.type/ref (:value-type (d/attribute *db* a))) (d/entity *db* v) v)
         :tx (d/entity *db* tx)
         :added added))
     Suggestable
     (suggest [_] (hfql [:e :a :v :tx :added]))
     ComparableRepresentation
     (comparable [datum] (into [] datum))))

#?(:clj (defmethod hfql-resolve `datomic.db/datum [[_ e a serialized-v tx added]] (dx/resolve-datom *db* e a serialized-v tx added)))

(defn db-name [db] (::db-name (meta db)))

#?(:clj
   (extend-type datomic.db.Db
     Identifiable
     (identify [db] (when-let [nm (db-name db)]
                      (let [id `(d/db ~nm)
                            ;; following db transformations are commutative, they can be applied in any order.
                            id (if (d/is-history db) `(d/history ~id) id)
                            id (if (d/is-filtered db) `(d/filter ~id) id) ; resolving will require DI to reconstruct the predicate
                            id (if (d/since-t db) `(d/since ~id ~(d/since-t db)) id)
                            id (if (d/as-of-t db) `(d/as-of ~id ~(d/as-of-t db)) id)
                            ;; datomic-uri is security-sensitive and is not part of db's identity. Resolving will required DI.
                            ]
                        id)))
     ComparableRepresentation
     (comparable [db] (db-name db))))

#?(:clj (defmethod hfql-resolve `d/db [[_ db-name]] ; resolve a Datomic database by name.
          (when *uri* ; Resolving a Datomic database by name is only possible when browsing by Datomic URI. But it isn't always allowed.
            (let [datomic-uri-db-name (dx/datomic-uri-db-name *uri*)]
              (when (or (= "*" datomic-uri-db-name) ; Injected Datomic URI allows listing databases and connecting to other databases.
                      (= db-name datomic-uri-db-name) ; Injected Datomic URI has a pinned database name, and doesn't allow connecting to other databases, but we are trying to resolve the current pinned one, which is always allowed.
                      *allow-listing-and-browsing-all-dbs*) ; defaults to false – must be explicitly allowed at the entrypoint.
                (with-meta (d/db (d/connect (dx/set-db-name-in-datomic-uri *uri* db-name)))
                  {::db-name db-name}))))))

#?(:clj (defmethod hfql-resolve `d/history [[_ db]] (d/history (hfql-resolve db))))
#?(:clj (defmethod hfql-resolve `d/filter  [[_ db]] (d/filter (hfql-resolve db) *filter-predicate*)))
#?(:clj (defmethod hfql-resolve `d/since [[_ db t]] (d/since (hfql-resolve db) t)))
#?(:clj (defmethod hfql-resolve `d/as-of [[_ db t]] (d/as-of (hfql-resolve db) t)))

(e/defn ConnectDatomic [datomic-uri]
  (e/server
    (Loader #(d/connect datomic-uri)
      {:Busy (e/fn [] (dom/h1 (dom/text "Waiting for Datomic connection ...")))
       :Failed (e/fn [error]
                 (dom/h1 (dom/text "Datomic transactor not found, see Readme.md"))
                 (dom/pre (dom/text (pr-str error))))})))

;; #?(:clj (defn slow-query [] (Thread/sleep 5000) (d/entity *db* @(requiring-resolve 'dustingetz.mbrainz/lennon))))

#?(:clj
   (def datomic-browser-sitemap
     {
      'databases (hfql {(databases) {* [^{::hfql/link ['.. [`(DatomicBrowser ~'%v) 'attributes]]} db-name d/db-stats]}}) ; TODO use '% instead of '%v and wire hf/resolve
      'attributes
      (hfql {(attributes)
             {* ^{::hfql/ColumnHeaderTooltip `SummarizeDatomicAttribute
                  ::hfql/select '(attribute-entity-detail %)}
              [^{::hfql/link '(attribute-detail %)
                 ::hfql/Tooltip `EntityTooltip}
               #(:db/ident %)
               ^{::hfql/Render `HumanFriendlyAttributeCount}
               attribute-count
               summarize-attr*
               #_:db/doc]}})

      'attribute-entity-detail
      (hfql {attribute-entity-detail ^{::hfql/Tooltip `SemanticTooltip
                                       ::hfql/ColumnHeaderTooltip `SummarizeDatomicAttribute}
             [^{::hfql/Render `EntityDbidCell}
              #(:db/id %)
              attribute-count
              summarize-attr*
              *]})

      'attribute-detail
      (hfql {attribute-detail
             {* ^{::hfql/ColumnHeaderTooltip `SummarizeDatomicAttribute
                  ::hfql/Tooltip `SemanticTooltip}
              [^{::hfql/link '(entity-detail %)}
               #(:db/id %)]}})

      'tx-detail
      (hfql {tx-detail
             {* [^{::hfql/link '(entity-detail :e)
                   ::hfql/Tooltip `EntityTooltip}
                 #(:e %)
                 ^{::hfql/link '(attribute-detail %)
                   ::hfql/Tooltip `EntityTooltip}
                 ^{::hfql/label :db/ident}
                 {:a :db/ident} ; FIXME
                 :v]}})

      'entity-detail
      (hfql {entity-detail ^{::hfql/Tooltip `SemanticTooltip} ; TODO want link and Tooltip instead
             [^{::hfql/Render `EntityDbidCell}
              #(:db/id %)
              *]})

      'entity-history
      (hfql {entity-history
             {* [^{::hfql/link '(entity-detail :e)
                   ::hfql/Tooltip `EntityTooltip} ; No need for a link on :e, it would always point to the same page.
                 #(:e %)
                 ^{::hfql/link '(attribute-detail :a)
                   ::hfql/Tooltip `EntityTooltip}
                 {:a :db/ident} ; FIXME
                 :v
                 ^{::hfql/link '(tx-detail %v)
                   ::hfql/Tooltip `EntityTooltip}
                 #(:tx %)
                 :added]}})}))

(e/defn InjectStyles []
  (e/client
    (dom/link (dom/props {:rel :stylesheet :href "/hyperfiddle/electric-forms.css"}))
    (dom/link (dom/props {:rel :stylesheet :href "/hyperfiddle/datomic-browser2.css"}))
    (Checkbox* false {:class "data-loader__enabled" :style {:position :absolute, :inset-block-start "1dvw", :inset-inline-end "1dvw"}})))

(e/defn BrowseDatomicDatabase [sitemap entrypoints db]
  (InjectStyles)
  (e/server
    (binding [e/*exports* (e/exports)
              hyperfiddle.navigator6.rendering/*server-pretty {datomic.query.EntityMap (fn [entity] (str "EntityMap[" (best-human-friendly-identity entity) "]"))}]
      (let [db-stats (e/server (e/Offload #(d/db-stats db)))]
        (binding [*db* db
                  *db-stats* db-stats
                  e/*bindings* (e/server (merge e/*bindings* {#'*db* db, #'*db-stats* db-stats}))]
          (Navigate sitemap entrypoints))))))

(e/defn BrowseDatomicByConnection [sitemap entrypoints datomic-conn]
  (e/server
    (binding [*conn* datomic-conn
              e/*bindings* (merge e/*bindings* {#'*conn* datomic-conn})]
      (BrowseDatomicDatabase sitemap entrypoints (e/Offload #(d/db datomic-conn))))))

(e/defn BrowseDatomicByURI [sitemap entrypoints datomic-uri]
  (e/server
    (let [db-name (dx/datomic-uri-db-name datomic-uri)]
      (binding [*uri* datomic-uri
                *db-name* db-name
                e/*bindings* (e/server (merge e/*bindings* {#'*uri* datomic-uri, #'*db-name* db-name}))]
        (if (= "*" db-name)
          (do (InjectStyles)
            (Navigate sitemap [^{::r/link ['..]} 'databases]))
          (BrowseDatomicByConnection sitemap entrypoints (e/server (ConnectDatomic datomic-uri))))))))

(comment
  (require '[dustingetz.mbrainz :refer [test-db lennon]])
  (set! *print-namespace-maps* false)

  (def !lennon (d/entity @test-db lennon))
  (def q (hfql {!lennon [:db/id ; careful of ref lifting, it lifts to EntityMap and REPL prints the map
                         :artist/name
                         :artist/type]}))
  (hfql/pull q)

  (def q (hfql {!lennon
                [:artist/name
                 {:track/_artists count}
                 type]}))
  (time (hfql/pull q)) ; "Elapsed time: 1.173292 msecs"
  := {'!lennon {:artist/name "Lennon", :track/_artists 30, 'type 'datomic.query.EntityMap}})
