#!/bin/bash
# ===============================================================
# Postgres init — runs once on first container startup, after
# 01-init.sh creates per-service roles and schemas.
# CREATE EXTENSION must run as superuser; agent_chat cannot.
# ===============================================================
set -e

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE EXTENSION IF NOT EXISTS vector;
EOSQL
