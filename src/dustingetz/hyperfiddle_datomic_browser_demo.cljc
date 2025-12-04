(ns dustingetz.hyperfiddle-datomic-browser-demo
  (:require
   [dustingetz.datomic-browser2 :refer [BrowseDatomicConnection BrowseDatomicURI #?(:clj datomic-browser-sitemap)]]
   #?(:clj [dustingetz.datomic-contrib2 :refer [datomic-uri-db-name set-db-name-in-datomic-uri]])

   [hyperfiddle.electric3 :as e]
   [hyperfiddle.electric-dom3 :as dom]
   [hyperfiddle.entrypoint2 :refer [Hyperfiddle]]))

(e/defn InjectAndRunHyperfiddle [ring-request datomic-uri]
  (e/client
    (binding [dom/node js/document.body
              e/http-request (e/server ring-request)]
      (dom/div (dom/props {:style {:display "contents"}}) ; mandatory wrapper div https://github.com/hyperfiddle/electric/issues/74
        (Hyperfiddle
          {`dustingetz.datomic-browser2/DatomicBrowser
           (e/fn ; DI
             ([]
              (e/server
                ;; Browse a given connection
                #_(BrowseDatomicConnection
                    (e/server datomic-browser-sitemap)
                    ['attributes] ; default
                    (e/server datomic-conn))
                ;; Browse by URI and eventually list databases if URI ends with `/*`
                (BrowseDatomicURI
                  (e/server datomic-browser-sitemap)
                  ['databases 'db 'attributes]
                  datomic-uri)))
             ([db-name]
              (e/server
                (if (= "*" (datomic-uri-db-name datomic-uri)) ; uri allows connection to any database
                  (BrowseDatomicURI
                    (e/server datomic-browser-sitemap)
                    ['databases]
                    (set-db-name-in-datomic-uri datomic-uri db-name))
                  (BrowseDatomicURI ; database name is pinned in the uri. Respect the provided uri and ignore the `db-name` argument.
                    (e/server datomic-browser-sitemap)
                    ['attributes]
                    datomic-uri)))))})))))

(defn hyperfiddle-demo-boot [ring-request datomic-uri]
  #?(:clj  (e/boot-server {} InjectAndRunHyperfiddle (e/server ring-request) (e/server datomic-uri)) ; client/server entrypoints must be symmetric
     :cljs (e/boot-client {} InjectAndRunHyperfiddle (e/server (e/amb)) (e/server (e/amb)))))    ; ring-request is server only, client sees nothing in place; same for datomic-uri
