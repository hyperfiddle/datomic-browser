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
  (d/get-database-names "datomic:dev://localhost:4334/*")
  (def proxy-uri "wss://index.clojure.net/agent")

  ;; Single-database — connect to specific db
  (def my-agent (agent/connect! proxy-uri nav/rich-sitemap (nav/make-setup-fn "datomic:dev://localhost:4334/mbrainz-full")))

  ;; Dynamic — multi-database with Inject route segment
  (def my-agent (agent/connect! proxy-uri nav/rich-sitemap (nav/make-setup-fn "datomic:dev://localhost:4334/*")))

  (agent/disconnect! my-agent))