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

# Build distributable start scripts during image build.
# Runtime will execute JVM apps directly (not sbt), which uses much less memory.
RUN sbt "altfinder/stage" "tracker/stage"

ENV APP=altfinder
ENV ALTFINDER_API_HOST=0.0.0.0
ENV ALTFINDER_API_PORT=10000
ENV JAVA_TOOL_OPTIONS="-Xms64m -Xmx256m -XX:+UseSerialGC"
ENV JAVA_OPTS="-Xms64m -Xmx256m -XX:+UseSerialGC"

EXPOSE 10000

CMD ["/bin/bash", "-lc", "export ALTFINDER_API_HOST=0.0.0.0; export ALTFINDER_API_PORT=${PORT:-10000}; if [ \"$APP\" = \"tracker\" ]; then ./tracker/target/universal/stage/bin/online-tracker; else ./altfinder/target/universal/stage/bin/alt-finder; fi"]
