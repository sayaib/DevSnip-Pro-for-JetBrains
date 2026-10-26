#!/usr/bin/env bash
# Database servers for webview/test/db-servers.mjs, in Docker, on fixed local ports.
#   scripts/db-containers.sh start   # start (or restart) them and wait until each accepts connections
#   scripts/db-containers.sh stop    # remove them
# Test-only passwords; the containers listen on 127.0.0.1 only.
set -euo pipefail

names=(devsnip-mysql devsnip-mariadb devsnip-sqlserver devsnip-oracle devsnip-clickhouse devsnip-redis)

stop() {
  docker rm -f "${names[@]}" >/dev/null 2>&1 || true
}

wait_for() {
  local name=$1 check=$2 seconds=${3:-180}
  for _ in $(seq 1 "$seconds"); do
    if eval "$check" >/dev/null 2>&1; then echo "ready: $name"; return 0; fi
    sleep 1
  done
  echo "NOT READY: $name"; docker logs --tail 20 "$name"; return 1
}

start() {
  stop
  docker run -d --name devsnip-mysql -p 127.0.0.1:33306:3306 -e MYSQL_ROOT_PASSWORD=devsnip -e MYSQL_DATABASE=app mysql:8.4 >/dev/null
  docker run -d --name devsnip-mariadb -p 127.0.0.1:33307:3306 -e MARIADB_ROOT_PASSWORD=devsnip -e MARIADB_DATABASE=app mariadb:11.4 >/dev/null
  docker run -d --name devsnip-sqlserver --platform linux/amd64 -p 127.0.0.1:31433:1433 -e ACCEPT_EULA=Y -e 'MSSQL_SA_PASSWORD=DevSnip!2026x' mcr.microsoft.com/mssql/server:2022-latest >/dev/null
  docker run -d --name devsnip-oracle -p 127.0.0.1:31521:1521 -e ORACLE_PASSWORD=devsnip gvenzl/oracle-free:23-slim >/dev/null
  docker run -d --name devsnip-clickhouse -p 127.0.0.1:38123:8123 -e CLICKHOUSE_PASSWORD=devsnip --ulimit nofile=262144:262144 clickhouse/clickhouse-server:24.8 >/dev/null
  docker run -d --name devsnip-redis -p 127.0.0.1:36379:6379 redis:7.4 redis-server --requirepass devsnip >/dev/null
  wait_for devsnip-redis "docker exec devsnip-redis redis-cli -a devsnip ping | grep -q PONG"
  wait_for devsnip-clickhouse "curl -sf 'http://127.0.0.1:38123/ping'"
  wait_for devsnip-mysql "docker exec devsnip-mysql mysqladmin -uroot -pdevsnip ping --silent"
  wait_for devsnip-mariadb "docker exec devsnip-mariadb mariadb-admin -uroot -pdevsnip ping --silent"
  wait_for devsnip-sqlserver "docker exec devsnip-sqlserver /opt/mssql-tools18/bin/sqlcmd -C -S localhost -U sa -P 'DevSnip!2026x' -Q 'SELECT 1'" 300
  wait_for devsnip-oracle "docker logs devsnip-oracle 2>&1 | grep -q 'DATABASE IS READY TO USE'" 420
}

case "${1:-start}" in
  start) start ;;
  stop) stop ;;
  *) echo "usage: $0 start|stop" >&2; exit 2 ;;
esac
