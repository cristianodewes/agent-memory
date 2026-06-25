# Reverse-proxy TLS templates

The agent-memory server **does not terminate TLS**. By design it binds loopback
(`agent-memory.server.address=127.0.0.1`) and speaks plain HTTP on `:8080`, with a shared
**bearer token** for auth and an **allowed-hosts** guard (issue #38). To reach it over HTTPS
from another machine, put a reverse proxy in front that terminates TLS and forwards to the
loopback server. Two ready-to-edit templates live here:

| Template | TLS | Opens an inbound port? | Best for |
|---|---|---|---|
| [`Caddyfile`](./Caddyfile) | Automatic Let's Encrypt | Yes (443/80) | A host with a public IP + DNS |
| [`cloudflared-config.yml`](./cloudflared-config.yml) | Cloudflare edge | **No** (outbound tunnel) | Home/NAT/private hosts, zero open ports |

Both forward to `http://127.0.0.1:8080`. Run the proxy on the **same host** as the server so
that hop stays on loopback and never crosses the network in clear text.

## Before you expose the server

Exposing the API off-box means enabling auth and the host guard (the loopback default leaves
auth off). Set these on the **server** (config file or environment):

```sh
# 1. Generate a high-entropy token.
java -jar agent-memory-server.jar --generate-auth-token

# 2. Enable auth and pin the token + the public hostname.
AGENT_MEMORY_AUTH_ENABLED=true
AGENT_MEMORY_AUTH_TOKEN=<the generated token>
AGENT_MEMORY_AUTH_ALLOWED_HOSTS=memory.example.com
# Keep the server on loopback; the proxy is the only thing that talks to it.
AGENT_MEMORY_SERVER_ADDRESS=127.0.0.1
```

When auth is enabled, every route (`/mcp`, `/hook`, `/handoff`, `/api/v1`, admin ops, `/web`)
requires `Authorization: Bearer <token>` (`/web` also accepts it as the HTTP Basic password);
only `/healthz` stays open. Point the Go client at the public URL with the same token:

```sh
AGENT_MEMORY_SERVER_URL=https://memory.example.com
AGENT_MEMORY_TOKEN=<the generated token>
```

## Caddy

1. Point a DNS `A`/`AAAA` record for `memory.example.com` at the host.
2. Edit [`Caddyfile`](./Caddyfile): replace `memory.example.com` and the ACME `email`.
3. Run it: `caddy run --config ./Caddyfile`. Caddy obtains and auto-renews the certificate.

## Cloudflare Tunnel

No inbound ports: `cloudflared` dials out to Cloudflare, which terminates TLS at your hostname.

```sh
cloudflared tunnel login
cloudflared tunnel create agent-memory          # prints the <TUNNEL_ID>
cloudflared tunnel route dns agent-memory memory.example.com
# Edit cloudflared-config.yml: set <TUNNEL_ID> and the hostname, then:
cloudflared tunnel --config ./cloudflared-config.yml run
```

> These are starting points, not turn-key production configs. Review them against your own
> threat model (rate limiting, IP allow-lists, WAF) before exposing real data.
