#!/usr/bin/env bash
set -Eeuo pipefail

export THISNOTE_DATA_ROOT="${THISNOTE_DATA_ROOT:-/app/data}"
export THISNOTE_PLUGINS_ROOT="${THISNOTE_PLUGINS_ROOT:-/app/runtime/plugins}"
export THISNOTE_PORT="${THISNOTE_PORT:-45917}"
export LOGGING_FILE_NAME="${LOGGING_FILE_NAME:-$THISNOTE_DATA_ROOT/logs/backend.log}"
export SERVER_ADDRESS="${SERVER_ADDRESS:-127.0.0.1}"

mkdir -p \
    "$THISNOTE_DATA_ROOT/logs" \
    /tmp/nginx-client \
    /tmp/nginx-proxy \
    /tmp/nginx-fastcgi \
    /tmp/nginx-uwsgi \
    /tmp/nginx-scgi

log() {
    printf '[thisnote-entrypoint] %s\n' "$*"
}

backend_pid=""
nginx_pid=""

shutdown() {
    trap - EXIT TERM INT
    if [[ -n "$nginx_pid" ]]; then
        kill -TERM "$nginx_pid" 2>/dev/null || true
    fi
    if [[ -n "$backend_pid" ]]; then
        kill -TERM "$backend_pid" 2>/dev/null || true
    fi
    wait "$nginx_pid" 2>/dev/null || true
    wait "$backend_pid" 2>/dev/null || true
}

trap shutdown EXIT TERM INT

log "启动后端：port=$THISNOTE_PORT, dataRoot=$THISNOTE_DATA_ROOT, pluginsRoot=$THISNOTE_PLUGINS_ROOT"
java ${JAVA_OPTS:-} -jar /app/app.jar &
backend_pid=$!

log "启动 Nginx：listen=8080"
nginx -c /app/nginx.conf -g 'daemon off;' &
nginx_pid=$!

log "服务进程已启动：backendPid=$backend_pid, nginxPid=$nginx_pid"
set +e
wait -n "$backend_pid" "$nginx_pid"
status=$?
set -e

if ! kill -0 "$backend_pid" 2>/dev/null; then
    log "后端已退出，容器即将停止：exitCode=$status"
fi
if ! kill -0 "$nginx_pid" 2>/dev/null; then
    log "Nginx 已退出，容器即将停止：exitCode=$status"
fi

exit "$status"