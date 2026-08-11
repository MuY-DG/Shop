# Production edge configuration

The files in this directory are the versioned source of truth for the public
Shop edge. DNS records are managed in DNSPod and point both production hosts to
`43.138.4.55`:

- `api.muybaby6.icu` proxies the Mini Program and callback API to
  `127.0.0.1:8080`.
- `admin.muybaby6.icu` serves the Admin SPA and proxies `/admin/**` plus
  `/realtime` to the backend.

V94 uses the account-level Mini Program message-push URL
`https://api.muybaby6.icu/wechat/mini/message` in **Safe mode + JSON**. This is
not a WeChat Pay callback and must never point at `pay-dev`. The backend route
is intentionally absent unless `SHOP_WECHAT_SERVICE_CARD_CALLBACK_ENABLED=true`;
its Token and 43-character EncodingAESKey live only in the ignored production
environment and must exactly match the WeChat console.

The V94 product fallback image is the merchant-owned static file
`admin/public/wechat/service-card-placeholder.png`, published as
`https://admin.muybaby6.icu/wechat/service-card-placeholder.png`. Deploy the
Admin release and verify an unauthenticated GET without Referer returns
`200`, `image/png`, and actual PNG bytes before enabling service-card outbound
work. Current COS product images require Referer and therefore are not the
default 2001 image source.

The production COS bucket keeps an explicit CORS rule for
`https://admin.muybaby6.icu` with `POST, GET, HEAD`, wildcard request headers,
the upload response headers documented in `docs/cos-direct-upload.md`, a
600-second preflight cache, and `Vary: Origin`. Do not replace it with `*`.
The bucket Referer allowlist also includes the exact `admin.muybaby6.icu`
hostname alongside the Mini Program and local-development entries; empty
Referer requests remain denied.

`shop-production-acme-bootstrap.conf` is only used while issuing the first
certificate. The live OpenResty configuration uses the two host-specific files.
The certificate is stored below `/opt/1panel/www/certbot` and is checked twice
daily by `shop-certbot-renew.timer`. The renewal unit pins the exact Certbot
image digest used for the initial issuance; update that digest deliberately
when upgrading Certbot.

Never commit certificate private keys, DNSPod credentials, application secrets,
or a built Admin `dist` directory.

Current production routing decision (recorded 2026-08-10): new payments use
runtime source `ENV`, whose new payment/refund callbacks use
`api.muybaby6.icu`. The `pay-dev.muybaby6.icu` route remains only for legacy
payment/refund callback retries and must not be removed until the historical
callback inventory and provider retry window are drained. It is not the Mini
Program release API or the V94 message-push host.

After the backend contract and Admin checks are green, deploy the already-built
SPA atomically with:

```bash
ops/deploy-admin.sh txcloud
```

The script creates an immutable release directory and switches the public
`index` symlink. It deliberately does not delete older releases.

Deploying the placeholder does not enable V94. Production already has the
account callback enabled and its Safe+JSON GET handshake verified; a V95 rollout
must not replace or expose that environment-only Token/AES material. Capture and
worker remain disabled until an operator uses **开发配置 → 微信服务动态** to save a database-backed
capture-only revision, inspect candidates and the durable queue, and only then
save a later revision that enables the worker for one controlled real payment within WeChat's 24-hour
activation window. The 30-day update window, callback receipt, and WeChat-side
display remain external acceptance evidence; a healthy edge or successful SPA
deployment does not prove message delivery.
