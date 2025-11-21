(ns dustingetz.datomic-browser2
  (:require [contrib.data :refer [get-with-residual-meta]]
            [hyperfiddle.electric3 :as e]
            ;; [hyperfiddle.hfql0 #?(:clj :as :cljs :as-alias) hfql]
            [hyperfiddle.hfql2 :as hfql :refer [hfql]]
            [hyperfiddle.navigator6 :as navigator :refer [HfqlRoot]]
            [hyperfiddle.navigator6.search :refer [*local-search]]
            [hyperfiddle.router5 :as r]
            [hyperfiddle.electric-dom3 :as dom]
            [hyperfiddle.electric-forms5 :refer [Checkbox*]]
            [dustingetz.loader :refer [Loader]]
            [dustingetz.str :refer [pprint-str blank->nil]]
            [clojure.string :as str]
            #?(:clj [datomic.api :as d])
            #?(:clj [datomic.lucene])
            #?(:clj [dustingetz.datomic-contrib2 :as dx])))

(e/declare ^:dynamic *uri*)
(e/declare ^:dynamic *conn*)
(e/declare ^:dynamic *db*)
(e/declare ^:dynamic *db-stats*) ; shared for perfs – safe to compute only once
(e/declare ^:dynamic *filter-predicate*) ; for d/filter

#?(:clj (defn databases []
          (when (= "*" (dx/datomic-uri-db-name *uri*)) ; security
            (->> (d/get-database-names *uri*)
              (hfql/navigable (fn [_index db-name] (hfql/resolve `(d/db ~db-name))))))))

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
                indexed? (indexed-attribute? *db* ident)
                fulltext? (fulltext-attribute? *db* ident)
                use-fulltext? (and fulltext? (not indexed?)) ; e.g. :track/artistCredit
                fulltext-query (fulltext-prefix-query search)
                eids (if (and use-fulltext? fulltext-query) ; prefer regular index lookup over fulltext search, if available. e.g. :artist/name
                       (d/q '[:find [?e ...] :in $ ?a ?search :where [(fulltext $ ?a ?search) [[?e]]]] *db* ident fulltext-query)
                       (if indexed?
                         (->> (d/index-range *db* ident search nil) ; end is exclusive, can't pass *search twice
                           (take-while #(if search (str/starts-with? (str (:v %)) search) true))
                           (map :e))
                         (->> (d/datoms *db* :aevt ident) ; e.g. :language/name
                           (filter #(if search (str/starts-with? (str (:v %)) search) true))
                           (map :e))))]
            (->> eids
              (hfql/filtered) ; optimisation – tag as already filtered, disable auto in-memory search
              (hfql/navigable (fn [_index ?e] (d/entity *db* ?e)))))))

#?(:clj (defn summarize-attr [db k] (->> (dx/easy-attr db k) (remove nil?) (map name) (str/join " "))))
#?(:clj (defn summarize-attr* [?!a] (when ?!a (summarize-attr *db* (:db/ident ?!a)))))

#?(:clj (defn datom->map [[e a v tx added]]
          (->> {:e e, :a a, :v v, :tx tx, :added added}
            (hfql/identifiable hash)
            (hfql/navigable (fn [key value]
                              (case key
                                :e (d/entity *db* e)
                                :a (d/entity *db* a)
                                :v (if (= :db.type/ref (:value-type (d/attribute *db* a))) (d/entity *db* v) v)
                                :tx (d/entity *db* tx)
                                :added value))))))

#?(:clj (defn tx-detail [!e] (->> (d/tx-range (d/log *conn*) (:db/id !e) (inc (:db/id !e)))
                               (into [] (comp (mapcat :data) (map datom->map))))))

#?(:clj (def entity-detail identity))
#?(:clj (def attribute-entity-detail identity))

#?(:clj (defn entity-history
          "history datoms in connection with a Datomic entity, both inbound and outbound statements."
          [!e]
          (let [history (d/history *db*)]
            (into [] (comp cat (map datom->map))
              [(d/datoms history :eavt (:db/id !e !e)) ; resolve both data and object repr, todo revisit
               (d/datoms history :vaet (:db/id !e !e))]))))

(e/defn ^::e/export EntityTooltip [entity edge value] ; FIXME edge is a custom hyperfiddle type
  (e/server (pprint-str (into {} (d/touch value))))) ; force conversion to map for pprint to wrap lines

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
                    (= :ref typ) (pprint-str (d/pull *db* ['*] value))
                    (= :identity unique?) (pprint-str (d/pull *db* ['*] [attribute #_(:db/ident (d/entity db a)) value])) ; resolve lookup ref
                    () nil))))))))

(e/defn ^::e/export SummarizeDatomicAttribute [_entity edge _value] ; FIXME props is a custom hyperfiddle type
  (e/server
    ((fn [attribute] (try (str/trimr (str attribute " " (summarize-attr *db* attribute))) (catch Throwable _)))
     (hfql/symbolic-edge edge)
     #_(hfql/resolved-form edge))))

(e/defn ^::e/export EntityDbidCell [entity edge value] ; FIXME edge is a custom hyperfiddle type
  (dom/span (dom/text (e/server (hfql/identify value)) " ") (r/link ['. [`(~'entity-history ~(hfql/identify entity))]] (dom/text "entity history"))))

#?(:clj (defn- entity-exists? [db eid] (and (some? eid) (seq (d/datoms db :eavt eid))))) ; d/entity always return an EntityMap, even for a non-existing :db/id
#?(:clj (defmethod hfql/resolve `d/entity [[_ eid]] (when (entity-exists? *db* eid) (d/entity *db* eid))))

#?(:clj (defn- best-human-friendly-identity [entity] (or #_(best-domain-level-human-friendly-identity entity) (:db/ident entity) (:db/id entity))))

#?(:clj ; list all attributes of an entity – including reverse refs.
   (extend-type datomic.query.EntityMap
     hfql/Identifiable
     (-identify [entity] (list `d/entity (best-human-friendly-identity entity)))
     hfql/Suggestable
     (-suggest [entity]
       (let [attributes (cons :db/id (dx/entity-attrs entity))
             reverse-refs (dx/reverse-refs (d/entity-db entity) (:db/id entity))
             reverse-attributes (->> reverse-refs (map first) (distinct) (map dx/invert-attribute))]
         (hfql/hfql* (hyperfiddle.hfql2.analyzer/analyze {} (vec (concat attributes reverse-attributes)))) ; TODO cleanup – not user friendly
         ))
     hfql/ComparableRepresentation
     (-comparable [entity] (str (best-human-friendly-identity entity))))) ; Entities are not comparable, but their printable representation (e.g. :db/ident) is.

#?(:clj ; list all attributes of an entity – including reverse refs.
   (extend-type datomic.db.Datum
     hfql/Suggestable
     (-suggest [_] (hfql [:e :a :v :tx :added]))
     hfql/ComparableRepresentation
     (-comparable [datum] (into [] datum))))

(defn db-name [db] (::db-name (meta db)))

#?(:clj
   (extend-type datomic.db.Db
     hfql/Identifiable
     (-identify [db] (when-let [nm (db-name db)]
                       (let [id `(d/db ~nm)
                             ;; following db transformations are commutative, they can be applied in any order.
                             id (if (d/is-history db) `(d/history ~id) id)
                             id (if (d/is-filtered db) `(d/filter ~id) id) ; resolving will require DI to reconstruct the predicate
                             id (if (d/since-t db) `(d/since ~id ~(d/since-t db)) id)
                             id (if (d/as-of-t db) `(d/as-of ~id ~(d/as-of-t db)) id)
                             ;; datomic-uri is security-sensitive and is not part of db's identity. Resolving will required DI.
                             ]
                         id)))
     hfql/ComparableRepresentation
     (-comparable [db] (db-name db))))

#?(:clj
   (defn security-select-db-name [datomic-uri db-name]
     (let [uri-db-name (dx/datomic-uri-db-name datomic-uri)]
       (cond
         ;; URI allows listing databases, and db-name is "*" ⇒ any db-name is allowed ⇒ "*" is allowed
         ;; A ∧ B
         (and (= "*" uri-db-name) (= "*" db-name))
         "*"
         ;; URI allows listing databases, and db-name is pinned ⇒ any db-name is allowed ⇒ db-name is allowed
         ;; A ∧ ¬B
         (and (= "*" uri-db-name) (not= "*" db-name))
         db-name
         ;; URI is pinned and db-name is "*" ⇒ only the uri's db-name is allowed ⇒ ignore db-name and keep the uri-pinned one
         ;; ¬A ∧ B
         (and (not= "*" uri-db-name) (= "*" db-name))
         uri-db-name
         ;; URI is pinned and db-name is pinned ⇒ db-name is allowed if it matches the uri's pinned one
         ;; ¬A ∧ ¬B
         :else ;; truth table is covered
         ;; db-name is allowed if it matches the uri's pinned one
         (if (= db-name uri-db-name)
           db-name ;; db-name is allowed
           nil)))))

#?(:clj (defmethod hfql/resolve `d/db [[_ insecure-db-name]]
          (when-let [secure-db-name (security-select-db-name *uri* insecure-db-name)]
            (with-meta (d/db (d/connect (dx/set-db-name-in-datomic-uri *uri* secure-db-name)))
              {::db-name secure-db-name}))))

#?(:clj (defmethod hfql/resolve `d/history [[_ db]] (d/history (hfql/resolve db))))
#?(:clj (defmethod hfql/resolve `d/filter  [[_ db]] (d/filter (hfql/resolve db) *filter-predicate*)))
#?(:clj (defmethod hfql/resolve `d/since [[_ db t]] (d/since (hfql/resolve db) t)))
#?(:clj (defmethod hfql/resolve `d/as-of [[_ db t]] (d/as-of (hfql/resolve db) t)))

(e/defn ConnectDatomic [datomic-uri]
  (e/server
    (Loader #(d/connect datomic-uri)
      {:Busy (e/fn [] (dom/h1 (dom/text "Waiting for Datomic connection ...")))
       :Failed (e/fn [error]
                 (dom/h1 (dom/text "Datomic transactor not found, see Readme.md"))
                 (dom/pre (dom/text (pr-str error))))})))

#?(:clj (defn slow-query [] (Thread/sleep 5000) (d/entity *db* @(requiring-resolve 'dustingetz.mbrainz/lennon))))

(defn current-db [] *db*)

#?(:clj
   (def datomic-browser-sitemap
     {
      'db (hfql {(current-db) [^{::hfql/link ['.. [`(DatomicBrowser ~'%v) 'attributes]]} db-name d/db-stats]})
      'databases (hfql {(databases) {* [^{::hfql/link ['.. [`(DatomicBrowser ~'%v) 'attributes]]} db-name d/db-stats]}}) ; TODO use '% instead of '%v and wire hf/resolve
      'attributes
      (hfql {(attributes)
             {* ^{::hfql/ColumnHeaderTooltip `SummarizeDatomicAttribute
                  ::hfql/select '(attribute-entity-detail %)}
              [^{::hfql/link '(attribute-detail %)
                 ::hfql/Tooltip `EntityTooltip}
               #(:db/ident %)
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
                 :added]}})

      'slow-query
      (hfql {(slow-query)
             [*]})

      'file
      (hfql [java.io.File/.getName
             {java.io.File/.listFiles {* ...}}]
        (clojure.java.io/file "."))

      'all-ns
      (hfql {(all-ns)
             {* [ns-name
                 meta
                 {ns-publics {vals {* [str meta]}}}]}})}))

#?(:clj
   (defn security-allowed-pages [sitemap datomic-uri db-name]
     (let [uri-db-name (dx/datomic-uri-db-name datomic-uri)]
       (set (keys (cond
                    ;; uri allows listing databases and db-name is "*" ⇒ can display all pages but the ones requiring a defined database ⇒ can't display 'db nor 'attributes
                    ;; A ∧ B
                    (and (= "*" uri-db-name) (= "*" db-name))
                    (dissoc sitemap 'db 'attributes)
                    ;; uri allows listing databases and db-name is pinned ⇒ can display all pages
                    ;; A ∧ ¬B
                    (and (= "*" uri-db-name) (not= "*" db-name))
                    sitemap
                    ;; uri is pinned and db-name matches ⇒ listing databases is forbidden ⇒ can't display 'databases
                    ;; ¬A ∧ B
                    (and (not= "*" uri-db-name) (= uri-db-name db-name))
                    (dissoc sitemap 'databases)
                    ;; uri is pinned and db-name doesn't match uri's ⇒ listing databases is forbidden and can't explore the given one ⇒ can't display 'databases nor 'db nor 'attributes
                    ;; ¬A ∧ ¬B
                    :else ;; truth table is covered
                    (dissoc sitemap 'databases 'db 'attributes) ; throwing is also an option
                    ))))))

(e/defn DatomicBrowser [sitemap entrypoints datomic-uri insecure-db-name] ; `datomic-uri` may end with `*`, allowing connection to any passed-in `db-name`.
  (dom/link (dom/props {:rel :stylesheet :href "/hyperfiddle/electric-forms.css"}))
  (dom/link (dom/props {:rel :stylesheet :href "/hyperfiddle/datomic-browser2.css"}))
  (Checkbox* false {:class "data-loader__enabled" :style {:position :absolute, :inset-block-start "1dvw", :inset-inline-end "1dvw"}})
  (e/server
    (binding [*uri* datomic-uri
              e/*bindings* (merge e/*bindings* {#'*uri* datomic-uri})
              e/*exports* (e/exports)
              hyperfiddle.navigator6.rendering/*server-pretty {datomic.query.EntityMap (fn [entity] (str "EntityMap[" (best-human-friendly-identity entity) "]"))}]
      (let [secure-db-name (security-select-db-name datomic-uri insecure-db-name)
            allowed-pages (security-allowed-pages sitemap datomic-uri secure-db-name) ; TODO check
            sitemap (select-keys sitemap allowed-pages)
            entrypoints (filter allowed-pages entrypoints)]
        (cond
          (or (nil? secure-db-name) (= "*" secure-db-name)) ; Can't connect without a valid db-name, but we can still render some pages like 'databases (if allowed by security-allowed-pages) or 'all-ns.
          (HfqlRoot sitemap entrypoints)
          :else ; secure-db-name can only be "*", nil, or a valid db-name
          (let [conn (e/server (ConnectDatomic (dx/set-db-name-in-datomic-uri datomic-uri secure-db-name)))
                db (e/server (e/Offload #(d/db conn)))
                db (e/server (vary-meta db assoc ::db-name secure-db-name))
                db-stats (e/server (e/Offload #(d/db-stats db)))]
            (binding [*conn* conn
                      *db* db
                      *db-stats* db-stats
                      e/*bindings* (e/server (merge e/*bindings* {#'*conn* conn, #'*db* db, #'*db-stats* db-stats}))]
              (HfqlRoot sitemap entrypoints))))))))

(comment
  (require '[dustingetz.mbrainz :refer [test-db lennon]])

  (def !lennon (d/entity @test-db lennon))
  (def q (hfql [:db/id
                :artist/name
                :artist/type] !lennon))
  (hfql/pull q)

  (def q (hfql [:db/id ; careful of ref lifting
                :artist/name
                :artist/type] !lennon))
  (hfql/pull q)

  (def q (hfql [:artist/name
                {:track/_artists count}
                type]
           !lennon))
  (hfql/pull q)

  (def q (hfql [:db/id :artist/name type]))
  (def x (hfql/seed {'% (d/entity @test-db lennon)} q))
  (hfql/pull x)

  (hfql/pull (hfql [:db/id :artist/name] (d/entity @test-db lennon)))


  (def !x (clojure.java.io/file "../hyperfiddle/src"))

  (def q (hfql [java.io.File/.getName
                java.io.File/.getAbsolutePath
                type]
           !x))

  (hfql/pull q)

  (set! *print-namespace-maps* false)

  (def q (hfql [java.io.File/.getName
                {java.io.File/.listFiles {* 2}}]
           !x))
  (time (hfql/pull q))

  (hfql/pull
    (hfql [java.io.File/.getName
           {java.io.File/.listFiles {* 1}}]
      (clojure.java.io/file ".")))

  (def q (hfql {(all-ns)
                {* [ns-name type]}}))

  (def q (hfql {* [ns-name type]} (all-ns)))
  (time (hfql/pull q))
  )

#?(:clj (extend-type java.io.File
          hfql/Identifiable (-identify [^java.io.File o] `(clojure.java.io/file ~(.getPath o)))
          hfql/Suggestable (-suggest [o] (hfql [java.io.File/.getName
                                                java.io.File/.getPath
                                                java.io.File/.getAbsolutePath
                                                {java.io.File/.listFiles {* ...}}]))))

#?(:clj (extend-type clojure.lang.Namespace
          hfql/Identifiable (-identify [ns] `(find-ns ~(ns-name ns)))
          hfql/Suggestable (-suggest [_] (hfql [ns-name ns-publics meta]))))

#?(:clj (extend-type clojure.lang.Var
          hfql/Identifiable (-identify [ns] `(find-var ~(symbol ns)))
          hfql/Suggestable (-suggest [_] (hfql [symbol meta .isMacro .isDynamic .getTag]))))

(comment
  (require '[dustingetz.mbrainz :refer [test-db]])

  (hfql/pull
    (hfql/seed {`hfql/*bindings* {#'*db* @test-db}}
      (hfql {(attributes)
             {* [:db/ident
                 attribute-count
                 summarize-attr*
                 :db/doc]}})))

  #_(def attributes' (binding [*db* test-db] (bound-fn* attributes)))

  (binding [*db* @test-db]
    (hfql/pull
      (hfql {(attributes)
             {* [:db/ident
                 summarize-attr*
                 :db/doc]}}))))

;; (hfql [:db/ident])
;; (hfql/aliased-form (ns-name *ns*) :db/ident)