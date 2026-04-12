FROM eclipse-temurin:17-jdk

WORKDIR /app

RUN apt-get update && apt-get install -y curl gnupg && \
    echo "deb https://repo.scala-sbt.org/scalasbt/debian all main" | tee /etc/apt/sources.list.d/sbt.list && \
    curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x99E82A75642AC823" | gpg --dearmor | tee /etc/apt/trusted.gpg.d/sbt.gpg > /dev/null && \
    apt-get update && apt-get install -y sbt && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

COPY . .

RUN sbt altfinder/compile

CMD ["sbt", "altfinder/runMain", "com.pamelak.onlinetracker.altfinder.HttpServer"]
