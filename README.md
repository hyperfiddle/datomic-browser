# Datomic-browser

## Links

* Hyperfiddle github: https://github.com/hyperfiddle/hyperfiddle
* Datomic-browser public readme: https://github.com/hyperfiddle/datomic-browser

## Getting started

Prerequsites
* `java -version` modern version, we use `openjdk version "23.0.2"`
* Clojure CLI https://clojure.org/guides/install_clojure

```shell
git clone git@gitlab.com:hyperfiddle/datomic-browser.git
cd datomic-browser
./datomic_fixtures.sh                   # Download Datomic w/ mbrainz dataset
./run_datomic.sh
clj -X:dev dev/-main :datomic-uri '"datomic:dev://localhost:4334/mbrainz-1968-1973"'
```

Repl: jack-in with `:dev` alias, then eval `(dev/-main {:datomic-uri "datomic:dev://localhost:4334/mbrainz-1968-1973"})`

## License
* free for individual use on local dev machines, mandatory runtime login (we are a business)
* using in prod requires a license, contact us.
* still working out the details