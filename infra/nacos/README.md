# Nacos config (placeholder)

Nacos runs in **standalone** mode for development (in-memory derby DB), so no
extra config is required for local development.

If production Nacos hardening is needed, this directory can hold:

- `application.properties` — overrides mounted into the container
- `mysql/` — MySQL schema if you switch from derby to MySQL persistence
- `cluster.conf` — node list when running a Nacos cluster

For now, environment variables in `docker-compose.yml` are the only knobs.

## Bootstrap auth token

Nacos 2.2.1+ requires an auth token (`NACOS_AUTH_TOKEN`) and identity key/value
(`NACOS_AUTH_IDENTITY_KEY/VALUE`) even in standalone mode. Generate them with:

```bash
openssl rand -base64 64 | head -c 64    # NACOS_AUTH_TOKEN (must be >= 32 bytes)
openssl rand -hex 16                    # NACOS_AUTH_IDENTITY_VALUE
```

Then put them in `.env` (see `.env.example`).
