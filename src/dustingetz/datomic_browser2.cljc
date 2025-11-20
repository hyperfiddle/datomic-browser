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
            [dustingetz.str :refer [pprint-str]]
            [clojure.string :as str]
            #?(:clj [datomic.api :as d])
            #?(:clj [dustingetz.datomic-contrib2 :as dx])))

(e/declare ^:dynamic *uri*)
(e/declare ^:dynamic *conn*)
(e/declare ^:dynamic *db*)
(e/declare ^:dynamic *db-stats*) ; shared for perfs – safe to compute only once

#?(:clj (defn databases []
          (when (= "*" (dx/datomic-uri-db-name *uri*)) ; security
            (->> (d/get-database-names *uri*)
              (hfql/navigable (fn [_index db-name]
                                (with-meta (d/db (d/connect (dx/set-db-name-in-datomic-uri *uri* db-name)))
                                  {::db-name db-name})))))))

#?(:clj (defn attributes "Datomic schema, with Datomic query diagnostics"
          []
          (let [x (d/query {:query '[:find [?e ...] :in $ :where [?e :db/valueType]] :args [*db*]
                            :io-context ::attributes, :query-stats ::attributes})
                x (get-with-residual-meta x :ret)]
            (hfql/navigable (fn [_index ?e] (d/entity *db* ?e)) x))))

#?(:clj (defn attribute-count "hello"
          [!e] (-> *db-stats* :attrs (get (:db/ident !e)) :count)))

#?(:clj (defn indexed-attribute? [db ident] (true? (:db/index (dx/query-schema db ident)))))

#?(:clj (defn attribute-detail [!e]
          (let [ident (:db/ident !e)
                search (not-empty *local-search)] ; capture dynamic for lazy take-while
            (->> (if (indexed-attribute? *db* ident)
                   (d/index-range *db* ident search nil) ; end is exclusive, can't pass *search twice
                   (d/datoms *db* :aevt ident))
              (take-while #(if search (str/starts-with? (str (:v %)) search) true))
              (map :e)
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

(defn db-name [db] (::db-name (meta db)))

#?(:clj
   (extend-type datomic.db.Db
     hfql/Identifiable
     (-identify [db] (when-let [nm (db-name db)]
                       `(d/db ~nm #_*uri* #_(d/basis-t db) #_(d/as-of-t db) #_(d/since-t db) #_(d/is-history db) #_(d/is-filtered db)))) ; what information is sensitive in this URL?
     hfql/ComparableRepresentation
     (-comparable [db] (db-name db))))

#?(:clj (defmethod hfql/resolve `d/db [[_ db-name]]
          (let [uri-db-name (dx/datomic-uri-db-name *uri*)]
            (if (or (= db-name uri-db-name) (= "*" uri-db-name)) ; security
              (with-meta (d/db (d/connect (dx/set-db-name-in-datomic-uri *uri* db-name)))
                {::db-name db-name})
              (throw (ex-info (str "Accessing database " db-name " is forbidden under the configured connection uri.") {:db-name db-name}))))))

(e/defn ConnectDatomic [datomic-uri]
  (e/server
    (Loader #(d/connect datomic-uri)
      {:Busy (e/fn [] (dom/h1 (dom/text "Waiting for Datomic connection ...")))
       :Failed (e/fn [error]
                 (dom/h1 (dom/text "Datomic transactor not found, see Readme.md"))
                 (dom/pre (dom/text (pr-str error))))})))

#?(:clj (defn slow-query [] (Thread/sleep 5000) (d/entity *db* @(requiring-resolve 'dustingetz.mbrainz/lennon))))

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

(e/defn DatomicBrowser [sitemap entrypoints datomic-uri db-name] ; `datomic-uri` may end with `*`, allowing connection to any passed-in `db-name`.
  (dom/link (dom/props {:rel :stylesheet :href "/hyperfiddle/electric-forms.css"}))
  (dom/link (dom/props {:rel :stylesheet :href "/hyperfiddle/datomic-browser2.css"}))
  (Checkbox* false {:class "data-loader__enabled" :style {:position :absolute, :inset-block-start "1dvw", :inset-inline-end "1dvw"}})
  (binding [*uri* datomic-uri
            e/*bindings* (e/server (merge e/*bindings* {#'*uri* datomic-uri}))
            e/*exports* (e/exports)
            hyperfiddle.navigator6.rendering/*server-pretty (e/server {datomic.query.EntityMap (fn [entity] (str "EntityMap[" (best-human-friendly-identity entity) "]"))})]
    (if (e/server (= "*" db-name (dx/datomic-uri-db-name datomic-uri))) ; no valid db-name available and *uri* allows "*". Can display: databases, all-ns, file, slow-query
      (HfqlRoot (e/server (dissoc sitemap 'attributes)) (e/server (remove #{'attributes} entrypoints))) ; can't render attributes without a db-name to connect to
      (let [conn (e/server (ConnectDatomic (dx/set-db-name-in-datomic-uri datomic-uri db-name))) ; secure – we know *uri* allows "*"
            db (e/server (e/Offload #(d/db conn)))
            db-stats (e/server (e/Offload #(d/db-stats db)))]
        (binding [*conn* conn
                  *db* db
                  *db-stats* db-stats
                  e/*bindings* (e/server (merge e/*bindings* {#'*conn* conn, #'*db* db, #'*db-stats* db-stats}))]
          (HfqlRoot sitemap entrypoints)))))) ; all sitemap available, including attributes

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