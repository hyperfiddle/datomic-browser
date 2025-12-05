(ns dustingetz.hyperfiddle-datomic-browser-demo
  (:require
   [dustingetz.datomic-browser2 :refer [BrowseDatomicByConnection BrowseDatomicByURI #?(:clj datomic-browser-sitemap)]]
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
                #_(BrowseDatomicByConnection
                    (dissoc datomic-browser-sitemap 'databases) ; can't list databases from a connection
                    ['attributes] ; default
                    datomic-conn)
                ;; Browse by URI
                (BrowseDatomicByURI
                  datomic-browser-sitemap
                  ['databases 'attributes]
                  datomic-uri)))
             ([db-name] ; selected db-name from the URL – comes from a link click in 'databases page
              (e/server
                (BrowseDatomicByURI
                  datomic-browser-sitemap
                  ['databases 'attributes]
                  (set-db-name-in-datomic-uri datomic-uri db-name))
                )))})))))

(defn hyperfiddle-demo-boot [ring-request datomic-uri]
  #?(:clj  (e/boot-server {} InjectAndRunHyperfiddle (e/server ring-request) (e/server datomic-uri)) ; client/server entrypoints must be symmetric
     :cljs (e/boot-client {} InjectAndRunHyperfiddle (e/server (e/amb)) (e/server (e/amb)))))    ; ring-request is server only, client sees nothing in place; same for datomic-uri
