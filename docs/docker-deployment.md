# Docker production deployment

The production topology keeps only the Spring Boot application on the application
server. MySQL and Redis run on separate private-network hosts or managed services;
they are intentionally not declared in `compose.prod.yaml`.

```text
Internet
  -> Caddy :443
  -> application server 127.0.0.1:8080
  -> shop-server container :8080
       -> MySQL private address :3306
       -> Redis private address :6379
```

## Security boundaries

- Do not expose MySQL `3306` or Redis `6379` to the public Internet.
- Put all three servers in the same private network and region.
- Allow the database and Redis security groups to receive traffic only from the
  application server's private IP or security group.
- Use separate least-privilege application accounts and long random passwords.
- Keep MySQL backups on the database service/server; container deployment does
  not replace database backups.
- Keep `.env.prod.local`, `secrets/`, database backups, and Redis credentials out
  of Git and Docker images.

On the application server, `.env.prod.local` must use the remote private
addresses rather than `127.0.0.1`:

```properties
# MySQL private DNS name or private IP; never use the public endpoint here.
SHOP_DB_URL=jdbc:mysql://mysql.internal:3306/hotpot_shop?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
# Dedicated least-privilege MySQL application account.
SHOP_DB_USERNAME=<production-database-user>
# Dedicated MySQL application password.
SHOP_DB_PASSWORD=<production-database-password>

# Redis private DNS name or private IP; 127.0.0.1 would mean the application container itself.
SHOP_REDIS_HOST=redis.internal
# Redis service port on the private network.
SHOP_REDIS_PORT=6379
# Redis logical database used by this application.
SHOP_REDIS_DATABASE=0
# Redis ACL username; leave empty only when the Redis deployment does not use ACL usernames.
SHOP_REDIS_USERNAME=<production-redis-user>
# Redis password; do not leave production Redis unauthenticated.
SHOP_REDIS_PASSWORD=<production-redis-password>
```

Use TLS for database and Redis connections when the selected service supports
it. Configure the provider's CA and hostname verification rather than disabling
certificate checks.

## One-time application-server preparation

Install Docker Engine and the Docker Compose plugin first. Then create the
deployment directories. The image runs as the fixed non-root UID/GID `10001`,
so bind-mounted files must be accessible to that identity:

```bash
sudo install -d -o 10001 -g 10001 -m 750 /opt/shop/shop-server
sudo install -d -o 10001 -g 10001 -m 750 /opt/shop/shop-server/secrets
sudo install -d -o 10001 -g 10001 -m 750 /opt/shop/shop-server/var
```

Place these files on the application server:

```text
/opt/shop/shop-server/compose.prod.yaml
/opt/shop/shop-server/.env.prod.local
/opt/shop/shop-server/secrets/<payment key files>
/opt/shop/shop-server/var/
```

After copying the production environment and payment key files, restrict their
permissions while keeping them readable by the container:

```bash
sudo chown 10001:10001 /opt/shop/shop-server/.env.prod.local
sudo chown -R 10001:10001 /opt/shop/shop-server/secrets /opt/shop/shop-server/var
sudo chmod 600 /opt/shop/shop-server/.env.prod.local
sudo find /opt/shop/shop-server/secrets -type f -exec chmod 600 {} \;
```

The paths configured by `WECHAT_PAY_PRIVATE_KEY_PATH`,
`WECHAT_PAY_PUBLIC_KEY_PATH`, and `SHOP_STORAGE_LOCAL_ROOT` must point into the
two mounted deployment directories, for example:

```properties
# Merchant private key path inside the container.
WECHAT_PAY_PRIVATE_KEY_PATH=/opt/shop/shop-server/secrets/apiclient_key.pem
# WeChat Pay public key path inside the container.
WECHAT_PAY_PUBLIC_KEY_PATH=/opt/shop/shop-server/secrets/wechatpay_public_key.pem
# Persistent LOCAL storage fallback path inside the container.
SHOP_STORAGE_LOCAL_ROOT=/opt/shop/shop-server/var/uploads
```

## Test before building

Run the backend tests locally:

```bash
cd /Users/muybaby/Project/Production/Shop/backend/shop-server
./mvnw test
```

The Docker build intentionally skips tests because tests are a separate release
gate and should fail before an image is published.

## Direct image upload over SSH

First check the server architecture:

```bash
ssh txcloud uname -m
```

Use `linux/amd64` for `x86_64`, or `linux/arm64` for `aarch64`. The development
Mac currently uses ARM64, so the platform must be specified when the server is
x86-64.

Build and load one server-compatible image locally:

```bash
cd /Users/muybaby/Project/Production/Shop/backend/shop-server
docker buildx build --platform linux/amd64 --load -t shop-server:local .
```

Stream that image directly to the server without creating a tar file:

```bash
docker save shop-server:local | ssh txcloud docker load
scp compose.prod.yaml txcloud:/opt/shop/shop-server/compose.prod.yaml
```

Start or update the container on the server:

```bash
ssh txcloud
cd /opt/shop/shop-server
docker compose -f compose.prod.yaml config --quiet
docker compose -f compose.prod.yaml up -d --force-recreate
docker compose -f compose.prod.yaml ps
docker compose -f compose.prod.yaml logs --tail=100 shop-server
```

During the one-time migration from the current systemd-managed JAR, stop and
disable `shop-server.service` before starting the container so both processes do
not compete for `127.0.0.1:8080`. Do this only after the container configuration,
remote MySQL, and remote Redis connectivity have been verified.

Caddy can continue proxying to `127.0.0.1:8080`; it does not need to know the
application is now in Docker.

## Registry-based deployment

For repeated releases, prefer a private registry such as Tencent Cloud TCR.
Build a versioned image and push it:

```bash
docker buildx build \
  --platform linux/amd64 \
  --tag <private-registry>/shop/shop-server:<release-tag> \
  --push .
```

After the server has logged in to the private registry, deploy that exact tag:

```bash
SHOP_IMAGE=<private-registry>/shop/shop-server:<release-tag> \
SHOP_IMAGE_PULL_POLICY=always \
docker compose -f compose.prod.yaml pull

SHOP_IMAGE=<private-registry>/shop/shop-server:<release-tag> \
SHOP_IMAGE_PULL_POLICY=always \
docker compose -f compose.prod.yaml up -d
```

Use immutable release tags instead of relying only on `latest`. Rollback means
starting the last verified tag with the same `.env.prod.local` and mounted data.

## Production checks

```bash
docker compose -f compose.prod.yaml ps
curl --fail http://127.0.0.1:8080/actuator/health
curl --fail https://pay-dev.muybaby6.icu/actuator/health
docker compose -f compose.prod.yaml logs --tail=200 shop-server
```

The Compose file publishes only `127.0.0.1:8080`, while the application listens
on `0.0.0.0` inside its isolated container. Do not remove the host-side
`127.0.0.1` binding.
