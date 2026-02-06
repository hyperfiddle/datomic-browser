(ns dustingetz.hyperfiddle-datomic-browser-demo
  (:require
   [dustingetz.datomic-browser2 :refer [BrowseDatomicByURI #?(:clj sitemap)]]
   #?(:clj [dustingetz.datomic-contrib2 :refer [set-db-name-in-datomic-uri]])

   [hyperfiddle.electric3 :as e]
   [hyperfiddle.electric-dom3 :as dom]
   [hyperfiddle.entrypoint2 :refer [Hyperfiddle]]))

(e/defn InjectAndRunHyperfiddle [ring-request datomic-transactor-uri]
  (e/client
   (binding [dom/node js/document.body
             e/http-request (e/server ring-request)]
     (dom/div (dom/props {:style {:display "contents"}}) ; mandatory wrapper div https://github.com/hyperfiddle/electric/issues/74
       (Hyperfiddle ; setup fiddle router
         {`dustingetz.datomic-browser2/DatomicBrowser ; a "fiddle" i.e. app, routable, encoded into the URL
          (e/fn ; fiddle entrypoint, any arguments are from the URL s-expression
                  ; /(dustingetz.datomic-browser2$DatomicBrowser)/ -- no db-name in URL expr
                  ; /dustingetz.datomic-browser2$DatomicBrowser/ -- equivalent fiddle url
            ([] (e/server (BrowseDatomicByURI sitemap ['databases] datomic-transactor-uri)))
                  ; /(dustingetz.datomic-browser2$DatomicBrowser,'mbrainz-full')/ -- inject dbname from URL expr
            ([db-name] (e/server (let [db-uri (set-db-name-in-datomic-uri datomic-transactor-uri db-name)]
                                   (BrowseDatomicByURI sitemap ['databases 'attributes] db-uri)))))})))))

(defn hyperfiddle-demo-boot [ring-request datomic-uri]
  #?(:clj  (e/boot-server {} InjectAndRunHyperfiddle (e/server ring-request) (e/server datomic-uri)) ; client/server entrypoints must be symmetric
     :cljs (e/boot-client {} InjectAndRunHyperfiddle (e/server (e/amb)) (e/server (e/amb)))))    ; ring-request is server only, client sees nothing in place; same for datomic-uri

; Use this to inject a secure connection, not uri (can't list databases from a connection)
#_(dustingetz.datomic-browser2/BrowseDatomicByConnection (dissoc sitemap 'databases) ['attributes] datomic-conn)