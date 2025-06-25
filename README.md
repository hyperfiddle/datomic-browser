# Hyperfiddle starter app

## What is it?

This is a demo of a new and unique kind of structure navigator, designed to run from your existing at-work classpath – i.e. integrated into your existing business application. Leveraging existing business queries without having to write REST endpoints, and respecting your existing web middleware stack. It is business oriented, customizable, and fully programmable. With a low-code approach and integrated into your system, it doesn’t suffer from ReTool, AirTable or Notion’s abstraction ceilings.



![Demo video](./docs/20250617_entity_browser.mp4)


### Entity Navigation
[![20250618 entity navigation](./docs/20250618_entity_navigation.png)](./docs/20250618_entity_navigation.png)

### Schema Explorer
[![20250618 schema explorer](./docs/20250618_schema_explorer.png)](./docs/20250618_schema_explorer.png)

## Use cases examples

* Microservice state observability in production
* Business-level database explorer
* Integrated customer support UIs

## Features

- programmable and extensible – you control all queries
- navigate any object, not just Datomic (just a demo)
- **leverage existing at-work classpath**
	- enrich your objects with derived fields and custom logic
    - embed it in your at-work server, call existing business queries
	- same security model as your production web app – plugs-in behind your middlewares
- No REST API
- progressive enhancement with web technologies (e.g. css)
- filtering, auto pagination, sorting
- deep linking

## Comparison to other tools

- Integrated UIs for business data – devs must write an API
	- Ag-Grid
		- Limited progressive enhancement – heavily optimized for grids
		- Biased towards client-side data – e.g. [No global search for remote data](https://www.ag-grid.com/react-data-grid/filter-quick/#server-side-data) 
	- Handsontable, Slickgrid
		- Spreadsheet editor model – immediate abstraction ceiling
		- Heavy integration boilerplate
- Devtools – can’t be integrated into an existing business app
	- REBL/Morse, Reveal
		- Heavy java client, no progressive enhancement
		- Remote system must expose a dedicated socket
	- Dataspex
		- Browser extension (users must install it) or standalone web app
		- Dedicated socket
	- Portal
		- Standalone app, REPL-like workflow
		- Remote system must push data to a known Portal instance


## Getting started

Follow these steps to ensure your setup is correct before going further:

1. Install demo data
```
git clone git@gitlab.com:hyperfiddle/hyperfiddle-starter-app.git
cd hyperfiddle-starter-app
./datomic_fixtures.sh # get Datomic (free) and example data
./run_datomic.sh
```
2. Run demo app. You’ll be asked to authenticate. The app will start on [localhost:8080](http://localhost:8080).
```
clj -X:dev dev/-main :datomic-uri '"datomic:dev://localhost:4334/mbrainz-1968-1973"'

# or at the REPL: user=> (dev/-main {:datomic-uri "datomic:dev://localhost:4334/mbrainz-1968-1973"})
```

## Customization

With the demo app running, look at and edit:
1. [datomic-browser.edn](./src/dustingetz/datomic_browser.edn):
	- it lists all queries – e.g. `attributes`, `attribute-detail`, etc.
	- it defines query columns with:
		- derived attributes – e.g. `attributes > (attribute-count %)`. `attribute-count` is a regular Clojure function.
		- links to other queries – e.g. `attributes > :db/ident` has a link to `attribute-detail`.
		- progressive enhancement – e.g. `attribute-entity-detail > :db/id` has a custom renderer.
2. [datomic-browser.cljc](./src/dustingetz/datomic_browser.cljc). It contains:
	- query definitions
	- derived attributes definitions – e.g. `attribute-count` and `summarize-attr*`
	- progressive enhancements and extensions
	- dependency injection – e.g. setting up `*conn*` and `*db*`

You can also run a Clojure REPL in your favourite editor – don’t forget the `:dev` deps alias – and run `dev/-main`.

## Integration

For now, integration is supported for Clojure Ring servers and ClojureScript clients built with Shadow-cljs.

1. Copy the [src/dustingetz](./src/dustingetz) folder into your app’s `src`.
2. Copy dependencies over from [deps.edn](./deps.edn) into your Clojure Ring server app.
3.  With [src/dev.cljc](./src/dev.cljc) as an example integration:
	- Server
		1. add the `wrap-electric-websocket` middleware to your ring middleware stack.
		2. compare and adapt your jetty websocket configuration with the example config.
	- Client
		1. copy over and adapt `(defn ^:dev/after-load -main [] ...)` and `(defn ^:dev/before-load stop! []...)` into your ClojureScript app `main` entrypoint. 
		2. Look at [shadow-cljs.edn](./shadow-cljs.edn) > `:demo`, adapt you dev build accordingly. Ensure your build lists `:build-hooks [(hyperfiddle.electric.shadow-cljs.hooks3/reload-clj)]` and the `main` you’ve copied over will be called.



<!--
## Mission

Mission (money)
Retool, Airtable, Notion

Business goals


Technical goals
identify and label the common structure shared between spreadsheets and crud apps in a credible, enterprise-compatible way that scales to more sophisticated apps, not less
leverage this structure as the foundation for or substrate of a next-gen application framework or engine (think Unity for enterprise apps) – turning programming into a higher order, creative medium



Architecture
IO engine (Electric Clojure) – pure functional structured concurrency framework

Prod (requires paid license)

License
free on local dev machines, mandatory runtime login
prod requires license, contact us, still working out the details

Product hypotheses and experiments
Runtime code observability of legacy systems for maintainers
How long should it take one to understand and master a file with 1000 LOC? What if there aren't tests?


-->

## License – WIP

free on local dev machines, mandatory runtime login
prod requires license, contact us, still working out the details
