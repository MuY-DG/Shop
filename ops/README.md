# Production edge configuration

The files in this directory are the versioned source of truth for the public
Shop edge. DNS records are managed in DNSPod and point both production hosts to
`43.138.4.55`:

- `api.muybaby6.icu` proxies the Mini Program and callback API to
  `127.0.0.1:8080`.
- `admin.muybaby6.icu` serves the Admin SPA and proxies `/admin/**` plus
  `/realtime` to the backend.

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

After the backend contract and Admin checks are green, deploy the already-built
SPA atomically with:

```bash
ops/deploy-admin.sh txcloud
```

The script creates an immutable release directory and switches the public
`index` symlink. It deliberately does not delete older releases.
