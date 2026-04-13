FROM eclipse-temurin:17-jdk AS builder

WORKDIR /app

RUN apt-get update && apt-get install -y curl gnupg && \
    echo "deb https://repo.scala-sbt.org/scalasbt/debian all main" | tee /etc/apt/sources.list.d/sbt.list && \
    curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x99E82A75642AC823" | gpg --dearmor | tee /etc/apt/trusted.gpg.d/sbt.gpg > /dev/null && \
    apt-get update && apt-get install -y sbt && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

# Cache dependency resolution as a separate layer (invalidated only when build.sbt or project/ changes)
COPY build.sbt .
COPY project/ project/
RUN sbt -J-Xmx1g -J-Xss2m update

COPY . .

RUN sbt -J-Xmx1g -J-Xss2m "altfinder/stage" "tracker/stage"

# ---- runtime image (no sbt, no JDK overhead) ----
FROM eclipse-temurin:17-jre

WORKDIR /app

COPY --from=builder /app/altfinder/target/universal/stage ./
COPY --from=builder /app/tracker/target/universal/stage ./tracker/
COPY entrypoint.sh ./entrypoint.sh
RUN chmod +x entrypoint.sh

ENV JAVA_TOOL_OPTIONS="-Xms64m -Xmx256m -XX:+UseSerialGC"

# Render injects $PORT at runtime; 10000 is the local/fallback default
EXPOSE 10000

ENTRYPOINT ["./entrypoint.sh"]
