#!/bin/bash
# ===============================================================
# Postgres init — runs once on first container startup.
# Creates per-service roles and schemas. Each Spring Boot service
# connects with its own role and only sees its own schema.
# Passwords come from env vars (set in docker-compose.yml).
# ===============================================================
set -e

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    -- Per-service roles
    CREATE ROLE agent_auth WITH LOGIN PASSWORD '${AUTH_DB_PASSWORD}';
    CREATE ROLE agent_chat WITH LOGIN PASSWORD '${CHAT_DB_PASSWORD}';
    CREATE ROLE agent_hub  WITH LOGIN PASSWORD '${HUB_DB_PASSWORD}';

    -- Per-service schemas, owned by their role
    CREATE SCHEMA IF NOT EXISTS auth AUTHORIZATION agent_auth;
    CREATE SCHEMA IF NOT EXISTS chat AUTHORIZATION agent_chat;
    CREATE SCHEMA IF NOT EXISTS hub  AUTHORIZATION agent_hub;

    -- Grant connect on the database (object-level grants happen via Flyway)
    GRANT CONNECT ON DATABASE ${POSTGRES_DB} TO agent_auth, agent_chat, agent_hub;

    -- Make sure each role's default search_path is its own schema
    ALTER ROLE agent_auth SET search_path = auth;
    ALTER ROLE agent_chat SET search_path = chat;
    ALTER ROLE agent_hub  SET search_path = hub;
EOSQL

echo "[init] Per-service roles and schemas created."
