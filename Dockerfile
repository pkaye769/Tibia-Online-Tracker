FROM eclipse-temurin:17-jdk

WORKDIR /app

ENV SBT_VERSION=1.8.2

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl bash \
    && curl -fsSL "https://github.com/sbt/sbt/releases/download/v${SBT_VERSION}/sbt-${SBT_VERSION}.tgz" \
      -o /tmp/sbt.tgz \
    && tar -xzf /tmp/sbt.tgz -C /usr/local \
    && rm /tmp/sbt.tgz \
    && rm -rf /var/lib/apt/lists/*

ENV PATH="/usr/local/sbt/bin:${PATH}"

# Cache dependency downloads as a separate layer before copying sources.
# This layer is only invalidated when build.sbt or project/ changes.
COPY build.sbt .
COPY project/ project/
RUN sbt -J-Xmx512m -J-Xss2m update

COPY . .

# Build distributable start scripts during image build.
# Runtime will execute JVM apps directly (not sbt), which uses much less memory.
RUN sbt -J-Xmx512m -J-Xss2m "altfinder/stage" "tracker/stage"

ENV APP=altfinder
ENV ALTFINDER_API_HOST=0.0.0.0
ENV ALTFINDER_API_PORT=10000
ENV JAVA_TOOL_OPTIONS="-Xms64m -Xmx256m -XX:+UseSerialGC"
ENV JAVA_OPTS="-Xms64m -Xmx256m -XX:+UseSerialGC"

EXPOSE 10000

CMD ["/bin/bash", "-lc", "export ALTFINDER_API_HOST=0.0.0.0; export ALTFINDER_API_PORT=${PORT:-10000}; if [ \"$APP\" = \"tracker\" ]; then ./tracker/target/universal/stage/bin/online-tracker; else ./altfinder/target/universal/stage/bin/alt-finder; fi"]
