(ns datomic-browser.main
  "Datomic Browser — self-contained single process.
   Starts a cloud proxy on the given port and auto-connects a Datomic agent
   that serves the Datomic browser UI (from the hyperfiddle-agent JAR).

   Usage:
     clj -M -m datomic-browser.main                                              ;; defaults
     clj -M -m datomic-browser.main datomic-uri datomic:dev://localhost:4334/*    ;; explicit URI
     clj -M -m datomic-browser.main http-port 9090                               ;; custom port"
  (:require [clojure.tools.logging :as log]
            [hyperfiddle.cloud-proxy :as proxy]
            [hyperfiddle.navigator-agent :as agent]
            [hyperfiddle.nav-cloud-agents :as nav-cloud-agents]
            [hyperfiddle.navigator6.rendering :as rendering]
            [hyperfiddle.hfql2 :refer [hfql]]
            [dustingetz.nav-datomic :as nav]
            [dustingetz.datomic-contrib2 :as dx]
            [dustingetz.str :refer [pprint-str]]
            [datomic.api :as d]))

(defn make-datomic-sitemap
  "Build the merged sitemap for multi-database Datomic browsing.
   The databases page links through Inject to attributes."
  []
  (merge (dissoc nav/rich-sitemap 'databases)
    {'databases (hfql {(nav/databases)
                       {* [^{:hyperfiddle.hfql2/link ['.. [`(~'Inject ~'%v) 'attributes]]}
                           nav/db-name
                           d/db-stats]}})}))

(defn make-setup-fn
  "Create the multi-arity setup-fn for dependency injection.
   Zero-arity:  static bindings for root (URI, security flag, rendering config).
   One-arity:   called per Inject route segment with db-name from URL."
  [datomic-uri]
  (fn
    ([] ;; Static bindings — always active on every page
     {#'nav/*uri* datomic-uri
      #'nav/*allow-listing-and-browsing-all-dbs* true
      #'rendering/*server-pretty
      {datomic.query.EntityMap
       (fn [entity] (str "EntityMap[" (dx/best-human-friendly-identity entity) "]"))}
      #'rendering/*tooltip-fn*
      (fn [_entity _edge value]
        (pprint-str (into {} (d/touch value)) :print-length 10 :print-level 2))})
    ([db-name] ;; Called when route has (Inject "mbrainz-full")
     (let [uri (dx/set-db-name-in-datomic-uri datomic-uri db-name)
           conn (d/connect uri)
           db (d/db conn)]
       {#'nav/*uri*      uri
        #'nav/*db-name*  db-name
        #'nav/*conn*     conn
        #'nav/*db*       db
        #'nav/*db-stats* (d/db-stats db)}))))

(defn -main
  "Entrypoint. Starts cloud proxy + auto-connects datomic agent.
   Args are clojure.main style key-value strings."
  [& {:strs [datomic-uri http-port]}]
  (let [port (or (some-> http-port parse-long) 8080)
        datomic-uri (or datomic-uri "datomic:dev://localhost:4334/*")]
    (log/info "Starting Datomic Browser" (pr-str {:datomic-uri datomic-uri :port port}))

    ;; 1. Start cloud proxy (serves the agent web UI from hyperfiddle-agent JAR)
    (def server (proxy/start-proxy! :port port))

    ;; 2. Auto-connect admin agent (shows connected agents dashboard)
    (def admin-agent
      (agent/connect! (str "ws://localhost:" port "/agent?id=admin")
        nav-cloud-agents/sitemap
        (fn [] {#'nav-cloud-agents/*agents* proxy/agents})))

    ;; 3. Auto-connect datomic agent
    (def datomic-agent
      (agent/connect! (str "ws://localhost:" port "/agent?id=datomic")
        (make-datomic-sitemap)
        (make-setup-fn datomic-uri)))

    (.addShutdownHook (Runtime/getRuntime)
      (Thread. (fn []
                 (agent/disconnect! datomic-agent)
                 (agent/disconnect! admin-agent)
                 (proxy/stop-proxy! server))))

    (log/info (format "Browse databases: http://datomic.localhost:%d" port))
    (log/info (format "Admin dashboard:  http://admin.localhost:%d" port))))
