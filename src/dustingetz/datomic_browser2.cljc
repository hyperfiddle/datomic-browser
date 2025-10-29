(ns dustingetz.datomic-browser2
  (:require [hyperfiddle.electric3 :as e]
            ;; [hyperfiddle.hfql0 #?(:clj :as :cljs :as-alias) hfql]
            [hyperfiddle.hfql2 :as hfql :refer [hfql]]
            [hyperfiddle.navigator6 :as navigator :refer [HfqlRoot]]
            [hyperfiddle.navigator6.search :refer [*local-search]]
            [hyperfiddle.router5 :as r]
            [hyperfiddle.electric-dom3 :as dom]
            [dustingetz.loader :refer [Loader]]
            [dustingetz.str :refer [pprint-str]]
            [clojure.string :as str]
            #?(:clj [datomic.api :as d])
            #?(:clj [dustingetz.datomic-contrib2 :as dx])))

(e/declare ^:dynamic *conn*)
(e/declare ^:dynamic *db*)
(e/declare ^:dynamic *db-stats*) ; shared for perfs – safe to compute only once

#?(:clj (defn attributes []
          (->> (d/query {:query '[:find [?e ...] :in $ :where [?e :db/valueType]] :args [*db*]
                         :io-context ::attributes, :query-stats ::attributes})
            (dx/query-stats-as-meta)
            (hfql/navigable (fn [_index ?e] (d/entity *db* ?e))))))

#?(:clj (defn attribute-count [!e] (-> *db-stats* :attrs (get (:db/ident !e)) :count)))

#?(:clj (defn indexed-attribute? [db a] (true? (:db/index (dx/query-schema db a)))))

#?(:clj (defn attribute-detail [a]
          (let [search (not-empty *local-search)] ; capture dynamic for lazy take-while
            (->> (if (indexed-attribute? *db* a)
                   (d/index-range *db* a search nil) ; end is exclusive, can't pass *search twice
                   (d/datoms *db* :aevt a))
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

#?(:clj (defn tx-detail [e] (->> (d/tx-range (d/log *conn*) e (inc e)) (into [] (comp (mapcat :data) (map datom->map))))))

#?(:clj (defn entity-detail [e] (if (instance? datomic.query.EntityMap e) e (d/entity *db* e)))) ; FIXME wart
#?(:clj (def attribute-entity-detail identity))

#?(:clj (defn entity-history [e]
          (let [history (d/history *db*)]
            (into [] (comp cat (map datom->map))
              [(d/datoms history :eavt (:db/id e e)) ; resolve both data and object repr, todo revisit
               (d/datoms history :vaet (:db/id e e))]))))

(e/defn ^::e/export EntityTooltip [entity edge value] ; FIXME edge is a custom hyperfiddle type
  (e/server (pprint-str (d/touch value))))

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

;; #?(:clj (defmethod hfql/resolve datomic.query.EntityMap [entity-map & _opts] (list `entity-detail (:db/id entity-map))))
;; #?(:clj (defmethod hfql/resolve `find-var [[_ var-sym]] (find-var var-sym))) ; example

#?(:clj ; list all attributes of an entity – including reverse refs.
   (extend-type datomic.query.EntityMap
     hfql/Identifiable
     (-identify [entity] (or #_(best-domain-level-human-friendly-identity entity) (:db/ident entity) (:db/id entity)))
     hfql/Suggestable
     (-suggest [entity]
       (let [attributes (cons :db/id (keys (d/touch entity)))
             reverse-refs (dx/reverse-refs (d/entity-db entity) (:db/id entity))
             reverse-attributes (->> reverse-refs (map first) (distinct) (map dx/invert-attribute))]
         (hfql/hfql* (hyperfiddle.hfql2.analyzer/analyze {} (vec (concat attributes reverse-attributes)))) ; TODO cleanup – not user friendly
         ))))

(e/defn ConnectDatomic [datomic-uri]
  (e/server
    (Loader #(d/connect datomic-uri)
      {:Busy (e/fn [] (dom/h1 (dom/text "Waiting for Datomic connection ...")))
       :Failed (e/fn [error]
                 (dom/h1 (dom/text "Datomic transactor not found, see Readme.md"))
                 (dom/pre (dom/text (pr-str error))))})))

#?(:clj
   (def datomic-browser-sitemap
     {`attributes (hfql {(attributes) {*  ^{::hfql/ColumnHeaderTooltip `SummarizeDatomicAttribute
                                            ::hfql/select              '(attribute-entity-detail %)}
                                       [^{::hfql/link    '(attribute-detail %)
                                          ::hfql/Tooltip `EntityTooltip}
                                        #(:db/ident %)

                                        attribute-count
                                        summarize-attr*
                                        :db/doc]}})

      'attribute-entity-detail
      (hfql {attribute-entity-detail ^{::hfql/Tooltip `SemanticTooltip
                                       ::hfql/ColumnHeaderTooltip `SummarizeDatomicAttribute}
             [^{::hfql/Render `EntityDbidCell}
              #(:db/id %)

              attribute-count
              summarize-attr*
              *]})

      'attribute-detail (hfql {attribute-detail {*  ^{::hfql/ColumnHeaderTooltip `SummarizeDatomicAttribute
                                                      ::hfql/Tooltip             `SemanticTooltip}
                                                 [^{::hfql/link '(entity-detail %)}
                                                  #(:db/id %)
                                                  *]}})

      'tx-detail (hfql {tx-detail {* [^{::hfql/link    '(entity-detail :e)
                                        ::hfql/Tooltip `EntityTooltip}
                                      #(:e %)
                                      ^{::hfql/link    '(attribute-detail %)
                                        ::hfql/Tooltip `EntityTooltip}
                                      ^{::hfql/label :db/ident}
                                      {:a :db/ident} ; FIXME
                                      :v]}})

      'entity-detail (hfql {entity-detail ^{::hfql/Tooltip `SemanticTooltip} ; TODO want link and Tooltip instead
                            [^{::hfql/Render `EntityDbidCell}
                             #(:db/id %)
                             *]})

      'entity-history (hfql {entity-history {* [^{::hfql/link    '(entity-detail :e)
                                                  ::hfql/Tooltip `EntityTooltip} ; No need for a link on :e, it would always point to the same page.
                                                #(:e %)
                                                ^{::hfql/link '(attribute-detail %)
                                                  ::hfql/Tooltip `EntityTooltip}
                                                {:a :db/ident} ; FIXME
                                                :v
                                                ^{::hfql/link    '(tx-detail %v)
                                                  ::hfql/Tooltip `EntityTooltip}
                                                #(:tx %)
                                                :added]}})}))
;; (hfql [:db/ident])
;; (hfql/aliased-form (ns-name *ns*) :db/ident)

(e/defn DatomicBrowser [sitemap entrypoints conn]
  (let [db (e/server (e/Offload #(d/db conn)))
        db-stats (e/server (e/Offload #(d/db-stats db)))]
    (binding [*conn* conn
              *db* db
              *db-stats* db-stats
              e/*bindings* (e/server (merge e/*bindings* {#'*conn* conn, #'*db* db, #'*db-stats* db-stats}))
              e/*exports*  (e/exports)
              hyperfiddle.navigator6.rendering/*server-pretty (e/server {datomic.query.EntityMap (fn [entity] (str "EntityMap[" (hfql/identify entity) "]"))})
              #_#_dustingetz.loader/Offload (e/fn [f _] (e/Offload f))
              ]
      (dom/link (dom/props {:rel :stylesheet :href "/hyperfiddle/electric-forms.css"}))
      (dom/link (dom/props {:rel :stylesheet :href "/hyperfiddle/datomic-browser2.css"}))
      (HfqlRoot sitemap entrypoints))))
