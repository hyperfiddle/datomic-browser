(ns dustingetz.learn-hfql-datomic
  "teaching namespace, unused by datomic-browser app"
  (:require
   [datomic.api :as d]
   dustingetz.datomic-contrib2  ; install HFQL protocols on EntityMap
   [hyperfiddle.hfql2 :as hfql :refer [hfql]]
   [hyperfiddle.hfql2.protocols :refer [Identifiable hfql-resolve Suggestable]]))

(comment
  "datomic entity"
  (d/get-database-names "datomic:dev://localhost:4334/*")
  (require '[dustingetz.mbrainz :refer [test-db lennon]])
  (def !lennon (d/entity @test-db lennon))
  (def q (time ; "Elapsed time: 0.14775 msecs"
           (hfql {!lennon [:db/id ; careful of ref lifting, it lifts to EntityMap and REPL prints the map
                           :artist/name
                           :artist/type]})))
  (def x (time ; "Elapsed time: 5.310125 msecs"
           (hfql/pull q)))
  x ; inspect it

 ; symbolic refs are lifted to object type by the HFQL protocols
  (-> x (get-in ['!lennon :db/id]) type) := datomic.query.EntityMap
  (-> x (get-in ['!lennon :artist/type]) type) := datomic.query.EntityMap

  (def q (hfql {!lennon
                [:artist/name
                 {:track/_artists [count]} ; note: no *, we count the collection not the entity
                 type]}))
  (time (hfql/pull q)) ; "Elapsed time: 1.64375 msecs"
  := {'!lennon {:artist/name "Lennon", :track/_artists {'count 30}, 'type datomic.query.EntityMap}}

  ; pull *
  (hfql/pull (hfql {!lennon [*]}))  ; careful: * is not quoted
  := {'!lennon
      {:artist/gid {:db/id 17592186066840}, ; EntityMap
       :artist/name "Lennon",
       :artist/sortName "Lennon",
       :artist/type {:db/id 17592186045421}}} ; EntityMap

  ; empty pull [] implies [*]
  (hfql/pull (hfql {(d/entity @test-db :db/doc)
                    []})))

(def ^:dynamic *app-db*)
(defn entity-exists? [db eid] (and (some? eid) (seq (d/datoms db :eavt eid))))
(defmethod hfql-resolve `d/entity [[_ eid]] (when (entity-exists? *app-db* eid) (d/entity *app-db* eid)))

(comment
  "the HFQL protocols"
  ; C.f. the extend-type on datomic.query.EntityMap in dustingetz.datomic-contrib2
  ; We use a few protocols and/or multimethods for dependency injection.
  ; First is `identify` and `resolve`, their goal is to be able to route to
  ; ANY object that you can name, while respecting that certain dependencies,
  ; like the application database, must be securely injected (as implied by the
  ; application entrypoint) and not part of thier public name.

  ; Identifiable - used to serialize the constructor to put in a URL
  (hfql/identify !lennon) := `(datomic.api/entity ~lennon) ; note no db, this is symbolic

  ; resolve - used to rehydrate the object from the public name, with secure application deps in scope
  (binding [*app-db* @test-db]
    (hfql-resolve `(datomic.api/entity ~lennon)))
  (type *1) := datomic.query.EntityMap

  ; Suggestable - used by [*] and also the column picker to sample what columns are available
  ; this is dynamic, and the interface is low level revealing hfql internals - todo improve
  (def star-q (hfql/suggest !lennon))
  (hfql/pull (hfql/seed {'% !lennon} star-q))
  (keys *1) := [:db/id
                :artist/sortName
                :artist/name
                :artist/type
                :artist/gid
                :abstractRelease/_artists ; reverse refs
                :release/_artists
                :track/_artists]
  )
