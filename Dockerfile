FROM clojure:temurin-11-tools-deps-1.12.0.1501 AS datomic-fixtures
WORKDIR /app
RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends unzip curl wget
COPY datomic_fixtures.sh datomic_fixtures.sh
COPY datomic_fixtures_mbrainz_full.sh datomic_fixtures_mbrainz_full.sh
# RUN ./datomic_fixtures.sh
RUN ./datomic_fixtures_mbrainz_full.sh
# Shaves 3Gb+ of docker image
RUN rm state/*.tar
RUN rm state/*.zip

FROM clojure:temurin-11-tools-deps-1.12.0.1501 AS build
WORKDIR /app
COPY deps.edn deps.edn
ARG VERSION
ENV VERSION=$VERSION
RUN clojure -A:prod -M -e ::ok       # preload – rebuilds if deps or commit version changes
RUN clojure -A:build:prod -M -e ::ok # preload

COPY shadow-cljs.edn shadow-cljs.edn
COPY src src
COPY src-prod src-prod
COPY src-build src-build
COPY resources resources

RUN clojure -X:prod:build uberjar :version "\"$VERSION\"" :build/jar-name "app.jar"

FROM amazoncorretto:11 AS app
# FROM clojure:temurin-11-tools-deps-1.12.0.1501 AS app
WORKDIR /app
COPY run_datomic.sh run_datomic.sh
COPY --from=datomic-fixtures /app/state state
COPY --from=build /app/target/app.jar app.jar
RUN echo -e "/state/\n/vendor/" > .gitignore

EXPOSE 8080
# CMD ./run_datomic.sh && java -cp app.jar clojure.main -m prod datomic-uri datomic:dev://localhost:4334/mbrainz-1968-1973
CMD ./run_datomic.sh && java -cp app.jar clojure.main -m prod datomic-uri datomic:dev://localhost:4334/mbrainz-full
