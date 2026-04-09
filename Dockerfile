FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/jre:openjdk-21

ENV TZ="Europe/Oslo"
ENV JDK_JAVA_OPTIONS='-XX:MaxRAMPercentage=75'

WORKDIR /app

COPY build/libs/*.jar /app/

CMD ["-jar", "app.jar"]

ARG BYGD_PA_NY='2026-01-30T10:18:45'
