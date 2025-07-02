# Hyperfiddle starter app

## Links

* Hyperfiddle github: https://github.com/hyperfiddle/hyperfiddle

## Getting started

```shell
git clone git@gitlab.com:hyperfiddle/hyperfiddle-starter-app.git
cd hyperfiddle-starter-app

# Install demo data
java -version              # we use openjdk version "23.0.2"
./datomic_fixtures.sh      # get Datomic (free) and example data
./run_datomic.sh

# Run demo app. You’ll be asked to authenticate.
# First via Clojure CLI to see it working. https://clojure.org/guides/install_clojure
clj -X:dev dev/-main :datomic-uri '"datomic:dev://localhost:4334/mbrainz-1968-1973"'

# Now jack in to REPL, :dev alias:
user=> (dev/-main {:datomic-uri "datomic:dev://localhost:4334/mbrainz-1968-1973"})
```

## License
* free for individual use on local dev machines, mandatory runtime login (we are a business)
* using in prod requires a license, contact us.
* still working out the details