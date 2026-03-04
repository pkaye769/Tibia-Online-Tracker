FROM eclipse-temurin:17-jdk

WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl gnupg ca-certificates bash \
    && echo "deb https://repo.scala-sbt.org/scalasbt/debian all main" > /etc/apt/sources.list.d/sbt.list \
    && curl -fsSL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x99E82A75642AC823" \
      | gpg --dearmor -o /etc/apt/trusted.gpg.d/sbt.gpg \
    && apt-get update \
    && apt-get install -y --no-install-recommends sbt \
    && rm -rf /var/lib/apt/lists/*

COPY . .

# Build caches during image build so startup is faster
RUN sbt "altfinder/compile" && sbt "tracker/compile"

ENV APP=altfinder
ENV ALTFINDER_API_HOST=0.0.0.0
ENV ALTFINDER_API_PORT=10000
ENV JAVA_TOOL_OPTIONS="-Xms64m -Xmx256m -XX:+UseSerialGC"
ENV SBT_OPTS="-Xms64m -Xmx256m -XX:+UseSerialGC -Dsbt.ci=true -Dsbt.supershell=false -Dsbt.server.autostart=false -Dsbt.server.forcestop=true"

EXPOSE 10000

CMD ["/bin/bash", "-lc", "export ALTFINDER_API_HOST=0.0.0.0; export ALTFINDER_API_PORT=${PORT:-10000}; if [ \"$APP\" = \"tracker\" ]; then sbt -batch -J-Xms64m -J-Xmx256m -J-XX:+UseSerialGC \"tracker/run\"; else sbt -batch -J-Xms64m -J-Xmx256m -J-XX:+UseSerialGC \"altfinder/run\"; fi"]
