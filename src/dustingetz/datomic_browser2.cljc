(ns dustingetz.datomic-browser2
  (:require [clojure.string :as str]
            [hyperfiddle.api :as hf]
            [hyperfiddle.electric3 :as e]
            [hyperfiddle.hfql2 :as hfql :refer [hfql]]
            [hyperfiddle.hfql2.protocols]
            [hyperfiddle.navigator6 :refer [HfqlRoot]]
            [hyperfiddle.router5 :as r]
            [hyperfiddle.electric-dom3 :as dom]
            [hyperfiddle.electric-forms5 :refer [Checkbox*]]
            [dustingetz.loader :refer [Loader]]
            [dustingetz.str :refer [pprint-str #?(:cljs format-number-human-friendly)]]
            #?(:clj [datomic.api :as d])
            #?(:clj [dustingetz.datomic-contrib2 :as dx])
            #?(:clj [dustingetz.nav-datomic :as nav]))) ; query fns, protocol extensions, resolvers

;; ── Electric dynamic vars ─────────────────────────────────────────
;; These are e/declare'd for Electric reactive binding in UI code (tooltips, renderers).
;; The plain CLJ dynamic vars live in dustingetz.nav-datomic and are bound via e/*bindings*.

(e/declare ^:dynamic *uri*)
(e/declare ^:dynamic *db-name*)
(e/declare ^:dynamic *conn*)
(e/declare ^:dynamic *db*)
(e/declare ^:dynamic *db-stats*)
(e/declare ^:dynamic *filter-predicate*)
(e/declare ^:dynamic *allow-listing-and-browsing-all-dbs*)

;; ── Electric UI components ────────────────────────────────────────

(e/defn ^::e/export EntityTooltip [entity edge value]
  (e/server (pprint-str (into {} (d/touch value)) :print-length 10 :print-level 2)))

(e/defn ^::e/export SemanticTooltip [entity edge value]
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
                (= :identity unique?) (pprint-str (d/pull *db* ['*] [attribute value])
                                        :print-length 10 :print-level 2)
                () nil))))))))

(e/defn ^::e/export SummarizeDatomicAttribute [_entity edge _value]
  (e/server
    ((fn []
       (try (str/trim (str (hfql/describe-formatted edge) " " (nav/summarize-attr *db* (hfql/symbolic-edge edge))))
         (catch Throwable _))))))

(e/defn ^::e/export EntityDbidCell [entity edge value]
  (dom/span (dom/text (e/server (hfql/identify value)) " ") (r/link ['. [`(~'entity-history ~(hfql/identify entity))]] (dom/text "entity history"))))

(e/defn ^::e/export HumanFriendlyAttributeCount [entity edge value]
  (e/client (dom/span (dom/props {:title (format-number-human-friendly value)})
              (dom/text (format-number-human-friendly value :notation :compact :maximumFractionDigits 1)))))

;; ── Sitemap (rich — with Electric tooltips/renderers) ─────────────

#?(:clj
   (def sitemap
     {
      'databases (hfql {(nav/databases) {* [^{::hfql/link ['.. [`(DatomicBrowser ~'%v) 'attributes]]} nav/db-name d/db-stats]}})

      'attributes
      (hfql {(nav/attributes)
             {* ^{::hfql/ColumnHeaderTooltip `SummarizeDatomicAttribute
                  ::hfql/select '(attribute-entity-detail %)}
              [^{::hfql/link '(attribute-detail %)
                 ::hfql/Tooltip `EntityTooltip}
               #(:db/ident %)
               ^{::hfql/Render `HumanFriendlyAttributeCount}
               nav/attribute-count
               nav/summarize-attr*
               #_:db/doc]}})

      'attribute-entity-detail
      (hfql {nav/attribute-entity-detail ^{::hfql/Tooltip `SemanticTooltip
                                           ::hfql/ColumnHeaderTooltip `SummarizeDatomicAttribute}
             [^{::hfql/Render `EntityDbidCell}
              #(:db/id %)
              nav/attribute-count
              nav/summarize-attr*
              *]})

      'attribute-detail
      (hfql {nav/attribute-detail
             {* ^{::hfql/ColumnHeaderTooltip `SummarizeDatomicAttribute
                  ::hfql/Tooltip `SemanticTooltip}
              [^{::hfql/link '(entity-detail %)}
               #(:db/id %)]}})

      'tx-detail
      (hfql {nav/tx-detail
             {* [^{::hfql/link '(entity-detail :e)
                   ::hfql/Tooltip `EntityTooltip}
                 #(:e %)
                 ^{::hfql/link '(attribute-detail :a)
                   ::hfql/Tooltip `EntityTooltip}
                 ^{::hfql/label :db/ident}
                 {:a :db/ident}
                 :v]}})

      'entity-detail
      (hfql {nav/entity-detail ^{::hfql/Tooltip `SemanticTooltip}
             [^{::hfql/Render `EntityDbidCell}
              #(:db/id %)
              *]})

      'entity-history
      (hfql {nav/entity-history
             {* [^{::hfql/link '(entity-detail :e)
                   ::hfql/Tooltip `EntityTooltip}
                 #(:e %)
                 ^{::hfql/link '(attribute-detail :a)
                   ::hfql/Tooltip `EntityTooltip}
                 {:a :db/ident}
                 :v
                 ^{::hfql/link '(tx-detail %v)
                   ::hfql/Tooltip `EntityTooltip}
                 #(:tx %)
                 :added]}})}))

;; ── Electric entrypoints ──────────────────────────────────────────

(e/defn InjectStyles []
  (e/client
    (dom/link (dom/props {:rel :stylesheet :href (e/server (str hf/*base-static-resource-path* "electric-forms.css"))}))
    (dom/link (dom/props {:rel :stylesheet :href (e/server (str hf/*base-static-resource-path* "datomic-browser2.css"))}))
    (Checkbox* false {:class "data-loader__enabled" :style {:position :absolute, :inset-block-start "1dvw", :inset-inline-end "1dvw"}})))

(e/defn ConnectDatomic [datomic-uri]
  (e/server
    (Loader #(d/connect datomic-uri)
      {:Busy (e/fn [] (dom/h1 (dom/text "Waiting for Datomic connection ...")))
       :Failed (e/fn [error]
                 (dom/h1 (dom/text "Datomic transactor not found, see Readme.md"))
                 (dom/pre (dom/text (pr-str error))))})))

(e/defn BrowseDatomicDatabase [sitemap entrypoints db]
  (InjectStyles)
  (e/server
    (binding [e/*exports* (e/exports)
              hyperfiddle.navigator6.rendering/*server-pretty {datomic.query.EntityMap (fn [entity] (str "EntityMap[" (dx/best-human-friendly-identity entity) "]"))}]
      (let [db-stats (e/server (e/Offload #(d/db-stats db)))]
        (binding [*db* db
                  *db-stats* db-stats
                  e/*bindings* (e/server (merge e/*bindings*
                                           {#'*db* db, #'*db-stats* db-stats
                                            #'nav/*db* db, #'nav/*db-stats* db-stats}))]
          (HfqlRoot sitemap entrypoints))))))

(e/defn BrowseDatomicByConnection [sitemap entrypoints datomic-conn]
  (e/server
    (binding [*conn* datomic-conn
              e/*bindings* (merge e/*bindings*
                             {#'*conn* datomic-conn
                              #'nav/*conn* datomic-conn})]
      (BrowseDatomicDatabase sitemap entrypoints (e/Offload #(d/db datomic-conn))))))

(e/defn BrowseDatomicByURI [sitemap entrypoints datomic-uri]
  (e/server
    (let [db-name (dx/datomic-uri-db-name datomic-uri)]
      (binding [*uri* datomic-uri
                *db-name* db-name
                e/*bindings* (e/server (merge e/*bindings*
                                        {#'*uri* datomic-uri, #'*db-name* db-name
                                         #'nav/*uri* datomic-uri, #'nav/*db-name* db-name}))]
        (if (= "*" db-name)
          (do (InjectStyles)
            (HfqlRoot sitemap [^{::r/link ['..]} 'databases]))
          (BrowseDatomicByConnection sitemap entrypoints (e/server (ConnectDatomic datomic-uri))))))))
