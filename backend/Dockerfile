# One Dockerfile, two images. `docker compose` builds it twice with different
# MODULE values, which is why the reactor is built whole each time rather than
# with -pl: the api and the simulateur share the parent pom, and a build that
# skipped the sibling would still have to resolve it.
#
# Java 21, matching the pinned stack. Nothing here chooses a version: the JDK
# tag matches java.version in backend/pom.xml and the base image is the same
# family for both stages.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /source

# The wrapper and the poms first, on their own layer. Dependencies change far
# less often than source, so a code edit reuses the resolved repository instead
# of downloading Spring again on every build.
COPY mvnw ./
COPY .mvn ./.mvn
COPY pom.xml ./
COPY api/pom.xml api/
COPY simulateur/pom.xml simulateur/
RUN chmod +x mvnw && ./mvnw -q -B dependency:go-offline

COPY api/src api/src
COPY simulateur/src simulateur/src

# The geometry parity fixture. Both modules' tests read it from the reactor
# root; without it a build that ran tests would fail on a missing file rather
# than on anything real.
COPY parite-geometrie.json ./

# Tests are not run here. They need a live PostgreSQL since phase 6 -- they fail
# rather than skip when it is unreachable, deliberately -- and a build stage has
# no database. `./mvnw test` against the running stack is the gate, not this.
RUN ./mvnw -q -B -DskipTests package

FROM eclipse-temurin:21-jre AS runtime
ARG MODULE
WORKDIR /app

# Not root. Neither process needs to write anywhere outside its own working
# directory, and the API is the one port exposed to the network.
RUN useradd --system --create-home --uid 10001 trino
USER trino

# The Boot plugin repackages into an executable jar; the *.original alongside it
# is the plain one, which is why the glob is anchored on the version.
COPY --from=build /source/${MODULE}/target/${MODULE}-*.jar /app/application.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/application.jar"]
