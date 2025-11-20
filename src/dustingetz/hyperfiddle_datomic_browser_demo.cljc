(ns dustingetz.hyperfiddle-datomic-browser-demo
  (:require
   [dustingetz.datomic-browser2 :refer [DatomicBrowser #?(:clj datomic-browser-sitemap)]]
   #?(:clj [dustingetz.datomic-contrib2 :refer [datomic-uri-db-name]])

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
           (e/server (e/fn ; DI
                       ([] (DatomicBrowser
                             (e/server datomic-browser-sitemap)
                             ['databases 'attributes] ; default
                             datomic-uri
                             (e/server (datomic-uri-db-name datomic-uri))))
                       ([db-name]
                        (DatomicBrowser
                          (e/server datomic-browser-sitemap)
                          ['databases 'attributes] ; default
                          datomic-uri
                          db-name))))})))))

(defn hyperfiddle-demo-boot [ring-request datomic-uri]
  #?(:clj  (e/boot-server {} InjectAndRunHyperfiddle (e/server ring-request) (e/server datomic-uri)) ; client/server entrypoints must be symmetric
     :cljs (e/boot-client {} InjectAndRunHyperfiddle (e/server (e/amb)) (e/server (e/amb)))))    ; ring-request is server only, client sees nothing in place; same for datomic-uri
