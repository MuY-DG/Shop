# Shop edge and deployment environments

The two public environments are intentionally isolated:

| SSH target | Purpose | API | Admin | Local secret files |
| --- | --- | --- | --- | --- |
| `txcloud` | development/integration | `https://api.muybaby6.icu` | `https://admin.muybaby6.icu` | existing `.env.prod.local` and `.env.infrastructure.local` |
| `shop` | production | `https://api.junxiangshiping.cn` | `https://admin.junxiangshiping.cn` | `.env.shop.local` and `.env.infrastructure.shop.local` |

Do not copy the txcloud database, Docker volumes, environment files, WeChat
credentials, payment configuration, COS credentials, or encryption keys into the
fresh production environment.

## OpenResty routing

The 1Panel website records remain the owner of the domain and certificate. The
versioned files under `ops/openresty/` document the effective custom configuration;
updating these files alone does not change a server.

- The API host sends normal HTTP traffic to `127.0.0.1:8080` and gives
  `/realtime` a WebSocket upgrade path.
- The Admin host serves the built SPA. `/admin/**` goes to the backend,
  `/realtime` upgrades to WebSocket, and every other unknown route falls back to
  `index.html` so refreshing a Vue route does not return 404.
- OpenResty overwrites `X-Forwarded-For` with `$remote_addr`. The backend trusts
  exactly one verified Docker bridge gateway, not arbitrary client-supplied proxy
  headers.

## Fresh production bootstrap

Prepare independent production files and a one-time Super credential:

```bash
backend/shop-server/scripts/init-prod-env.sh --environment shop
backend/shop-server/scripts/init-bootstrap-admin.sh shop
```

Set the verified Docker bridge gateway in `.env.shop.local`, then deploy a clean,
committed revision:

```bash
backend/shop-server/scripts/deploy-prod.sh shop
pnpm --dir admin check
CI=true pnpm --dir admin build
pnpm --dir admin check:generated-imports
ops/deploy-admin.sh shop
```

The bootstrap credential is stored only in the ignored, mode-600 file
`backend/shop-server/.env.bootstrap-admin.shop.local`. After the first login,
change the password and remove the plaintext credential file.

For development deployments, use `txcloud`. The backend script retains backward
compatibility with the existing canonical txcloud secret filenames, while Admin
selects the development host automatically:

```bash
backend/shop-server/scripts/deploy-prod.sh txcloud
ops/deploy-admin.sh txcloud
```

Never commit certificates, API credentials, database passwords, encryption keys,
bootstrap credentials, or built `dist` output.

## Provider-side configuration

The production Mini Program message-push URL is
`https://api.junxiangshiping.cn/wechat/mini/message`. WeChat request/socket/upload/
download legal domains, the Mini Program AppID/secret, payment merchant binding and
callback approval, ICP filing, COS CNAME, COS CORS, and COS Referer rules are
provider-console actions; server deployment does not prove those external settings
or real payment/message delivery.

The service-card fallback image, when enabled later, is published from
`https://admin.junxiangshiping.cn/wechat/service-card-placeholder.png`. Verify its
real PNG response before enabling outbound service-card work.
