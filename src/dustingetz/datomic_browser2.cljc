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

(e/declare ^:dynamic *uri*)
(e/declare ^:dynamic *db-name*)
(e/declare ^:dynamic *conn*)
(e/declare ^:dynamic *db*)
(e/declare ^:dynamic *db-stats*) ; shared for perfs – safe to compute only once
(e/declare ^:dynamic *filter-predicate*) ; for d/filter
(e/declare ^:dynamic *allow-listing-and-browsing-all-dbs*)

#?(:clj (defn databases []
          (let [database-names-list (cond
                                      (= "*" (dx/datomic-uri-db-name *uri*)) (d/get-database-names *uri*) ; Throws if datomic uri doesn't end with `*`.
                                      *allow-listing-and-browsing-all-dbs* (d/get-database-names (dx/set-db-name-in-datomic-uri *uri* "*"))
                                      :else (list *db-name*))] ; only list current db
            (->> database-names-list
              (hfql/navigable (fn [_index db-name] (hfql-resolve `(d/db ~db-name))))))))

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

#?(:clj (defmethod hfql-resolve `d/db [[_ db-name]]
          ;; Resolve db by name if it matches the injected datomic uri or if injected datomic uri allows all dbs to be listed (ends with `*`).
          (let [datomic-uri-db-name (dx/datomic-uri-db-name *uri*)]
            (when (or (= "*" datomic-uri-db-name) (= db-name datomic-uri-db-name) *allow-listing-and-browsing-all-dbs*)
              (with-meta (d/db (d/connect (dx/set-db-name-in-datomic-uri *uri* db-name)))
                {::db-name db-name})))))

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

(e/defn BrowseDatomicByConnection [sitemap entrypoints datomic-conn]
  (dom/link (dom/props {:rel :stylesheet :href "/hyperfiddle/electric-forms.css"}))
  (dom/link (dom/props {:rel :stylesheet :href "/hyperfiddle/datomic-browser2.css"}))
  (Checkbox* false {:class "data-loader__enabled" :style {:position :absolute, :inset-block-start "1dvw", :inset-inline-end "1dvw"}})
  (e/server
    (binding [e/*exports* (e/exports)
              hyperfiddle.navigator6.rendering/*server-pretty {datomic.query.EntityMap (fn [entity] (str "EntityMap[" (best-human-friendly-identity entity) "]"))}]
      (let [db (e/server (e/Offload #(d/db datomic-conn)))
            db-stats (e/server (e/Offload #(d/db-stats db)))]
        (binding [*conn* datomic-conn
                  *db* db
                  *db-stats* db-stats
                  e/*bindings* (e/server (merge e/*bindings* {#'*conn* datomic-conn, #'*db* db, #'*db-stats* db-stats}))]
          (Navigate sitemap entrypoints))))))

(e/defn BrowseDatomicByURI [sitemap entrypoints datomic-uri & {:keys [allow-listing-and-browsing-all-dbs?]
                                                               :or {allow-listing-and-browsing-all-dbs? false}}]
  (dom/link (dom/props {:rel :stylesheet :href "/hyperfiddle/electric-forms.css"}))
  (dom/link (dom/props {:rel :stylesheet :href "/hyperfiddle/datomic-browser2.css"}))
  (Checkbox* false {:class "data-loader__enabled" :style {:position :absolute, :inset-block-start "1dvw", :inset-inline-end "1dvw"}})
  (e/server
    (binding [e/*exports* (e/exports)
              hyperfiddle.navigator6.rendering/*server-pretty {datomic.query.EntityMap (fn [entity] (str "EntityMap[" (best-human-friendly-identity entity) "]"))}]
      (if (= "*" (dx/datomic-uri-db-name datomic-uri)) ; we don't know which db to connect to, but we can still list databases.
        (binding [*uri* datomic-uri
                  e/*bindings* (e/server (merge e/*bindings* {#'*uri* datomic-uri}))]
          (Navigate sitemap entrypoints))
        (let [datomic-conn (e/server (ConnectDatomic datomic-uri))
              db (e/server (e/Offload #(d/db datomic-conn)))
              db-name (dx/datomic-uri-db-name datomic-uri)
              db (e/server (vary-meta db assoc ::db-name db-name))
              db-stats (e/server (e/Offload #(d/db-stats db)))]
          (binding [*allow-listing-and-browsing-all-dbs* allow-listing-and-browsing-all-dbs?
                    *uri* datomic-uri
                    *db-name* db-name
                    *conn* datomic-conn
                    *db* db
                    *db-stats* db-stats
                    e/*bindings* (e/server (merge e/*bindings* {#'*uri* datomic-uri #'*conn* datomic-conn, #'*db* db, #'*db-stats* db-stats #'*db-name* db-name}))]
            (Navigate sitemap entrypoints)))))))



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
          Identifiable (identify [^java.io.File o] `(clojure.java.io/file ~(.getPath o)))
          Suggestable (suggest [o] (hfql [java.io.File/.getName
                                          java.io.File/.getPath
                                          java.io.File/.getAbsolutePath
                                          {java.io.File/.listFiles {* ...}}]))))

#?(:clj (defmethod hfql-resolve 'clojure.java.io/file [[_ file-path-str]] (clojure.java.io/file file-path-str)))

#?(:clj (extend-type clojure.lang.Namespace
          Identifiable (identify [ns] `(find-ns ~(ns-name ns)))
          Suggestable (suggest [_] (hfql [ns-name ns-publics meta]))))

#?(:clj (defmethod hfql-resolve `find-ns [[_ ns-sym]] (find-ns ns-sym)))

#?(:clj (extend-type clojure.lang.Var
          Identifiable (identify [ns] `(find-var ~(symbol ns)))
          Suggestable (suggest [_] (hfql [symbol meta .isMacro .isDynamic .getTag]))))

#?(:clj (defmethod hfql-resolve `find-var [[_ var-sym]] (find-var var-sym)))


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