(ns user)
(print "[user] loading dev... ") (flush)
(require 'dev)
(println "Ready.")

(comment
  ;; ── Demo: connect an agent with datomic browsing ──────────────
  (require
    '[datomic.api :as d]
    '[dustingetz.nav-datomic :as nav]
    '[hyperfiddle.navigator-agent :as agent])

  (require '[clojure.repl.deps :refer [add-libs]])
  (add-libs '{com.hyperfiddle/hyperfiddle-agent {:mvn/version "v0-alpha-SNAPSHOT"}})

  (require '[hyperfiddle.navigator-agent :as agent] '[hyperfiddle.hfql2 :refer [hfql]])

  (d/get-database-names "datomic:dev://localhost:4334/*")
  (def uri "datomic:dev://localhost:4334/mbrainz-1968-1973")
  (def test-db (d/db (d/connect uri)))

  (def ^:dynamic *db*)

  (defn schema []
    (d/q '[:find [(pull ?e [:db/ident
                            {:db/valueType [:db/ident]}
                            {:db/cardinality [:db/ident]}
                            *]) ...]
           :where [?e :db/valueType _]] *db*))

  (def sitemap {`schema (hfql {(schema) {* [:db/ident
                                            {:db/valueType {:db/ident name}}
                                            {:db/cardinality {:db/ident name}}]}})})

  (def agent (agent/connect! "wss://index.clojure.net/agent" sitemap
               (fn [] {#'*db* test-db})))



  ;; Single-database — connect to specific db
  (def agent (agent/connect! proxy-uri nav/sitemap (nav/make-setup-fn "datomic:dev://localhost:4334/mbrainz-full")))

  ;; Dynamic — multi-database with Inject route segment
  (def agent (agent/connect! proxy-uri nav/sitemap (nav/make-setup-fn "datomic:dev://localhost:4334/*")))

  (agent/disconnect! agent))