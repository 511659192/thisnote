FROM docker.1ms.run/library/node:22-alpine AS web-build
RUN corepack enable && corepack prepare pnpm@11.23.0 --activate
WORKDIR /workspace
COPY frontend/ ./
RUN pnpm install --frozen-lockfile
RUN pnpm -r build

FROM docker.1ms.run/library/maven:3.9.11-eclipse-temurin-21 AS backend-build
WORKDIR /workspace/backend
COPY backend/ ./
RUN mvn -B -ntp -DskipTests package

FROM docker.1ms.run/library/eclipse-temurin:21-jre-jammy

ARG DEBIAN_FRONTEND=noninteractive
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates \
        curl \
        nginx \
        python3 \
        python3-pip \
        python3-venv \
        tini \
    && python3 -m venv /opt/graphify \
    && /opt/graphify/bin/pip install --no-cache-dir graphifyy==0.9.92 \
    && ln -s /opt/graphify/bin/graphify /usr/local/bin/graphify \
    && rm -rf /var/lib/apt/lists/*

RUN /opt/graphify/bin/pip install --no-cache-dir 'graphifyy[openai]==0.9.92'

RUN groupadd --system --gid 10001 thisnote \
    && useradd --system --uid 10001 --gid thisnote --home-dir /home/thisnote --create-home thisnote

WORKDIR /app
COPY --from=web-build /workspace/host/dist/ /app/web/
COPY --from=web-build /workspace/runtime/plugins/ /app/web/plugin-assets/
COPY --from=backend-build /workspace/backend/app/target/thisnote-backend.jar /app/app.jar
COPY --from=backend-build /workspace/backend/runtime/plugins/ /app/runtime/plugins/
COPY docker/plugins-disabled.txt /app/runtime/plugins/disabled.txt
COPY docker/nginx.conf /app/nginx.conf
COPY docker/entrypoint.sh /app/entrypoint.sh

RUN mkdir -p \
        /app/data/logs \
        /tmp/nginx-client \
        /tmp/nginx-proxy \
        /tmp/nginx-fastcgi \
        /tmp/nginx-uwsgi \
        /tmp/nginx-scgi \
    && chmod 0755 /app/entrypoint.sh \
    && chown -R thisnote:thisnote \
        /app \
        /tmp/nginx-client \
        /tmp/nginx-proxy \
        /tmp/nginx-fastcgi \
        /tmp/nginx-uwsgi \
        /tmp/nginx-scgi

USER thisnote
ENV THISNOTE_DATA_ROOT=/app/data \
    THISNOTE_PLUGINS_ROOT=/app/runtime/plugins \
    THISNOTE_PORT=45917 \
    LOGGING_FILE_NAME=/app/data/logs/backend.log \
    SERVER_ADDRESS=127.0.0.1 \
    TZ=Asia/Shanghai

EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=45s --retries=8 \
    CMD curl -fsS http://127.0.0.1:8080/api/health/get >/dev/null || exit 1

ENTRYPOINT ["/usr/bin/tini", "--", "/app/entrypoint.sh"]