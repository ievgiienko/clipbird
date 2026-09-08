# Deployment Guide

## Local Docker Deployment

### Prerequisites
- Docker and Docker Compose installed

### Steps

```bash
# 1. Copy and edit environment file
cp .env.example .env

# 2. Build and start
docker compose up --build -d

# 3. Check status
docker compose ps
docker compose logs -f app

# 4. Access the application
# http://localhost:8080
```

### Stopping
```bash
docker compose down          # Stop containers
docker compose down -v       # Stop and remove data volume
```

## Building Docker Image Manually

```bash
# Build
docker build -t secure-clipboard:latest .

# Run (requires MySQL accessible)
docker run -p 8080:8080 \
  -e DB_HOST=host.docker.internal \
  -e DB_PORT=3306 \
  -e DB_NAME=secure_clipboard \
  -e DB_USER=clipboard_user \
  -e DB_PASSWORD=clipboard_pass \
  secure-clipboard:latest
```

## Production Deployment on Cloud VM

### 1. Server Setup

```bash
# Install Docker
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER

# Install Docker Compose
sudo apt-get install docker-compose-plugin
```

### 2. Deploy Application

```bash
# Clone repo to server
git clone <repo-url> /opt/secure-clipboard
cd /opt/secure-clipboard

# Configure environment
cp .env.example .env
# Edit .env with production values:
#   - Strong MYSQL_ROOT_PASSWORD
#   - Strong DB_PASSWORD
#   - SPRING_PROFILES_ACTIVE=prod
#   - CLIPBOARD_TTL_HOURS as needed

# Start
docker compose up --build -d
```

### 3. Reverse Proxy (Nginx)

HTTPS is **required** for production. Example Nginx configuration:

```nginx
server {
    listen 443 ssl http2;
    server_name clipboard.example.com;

    ssl_certificate /etc/letsencrypt/live/clipboard.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/clipboard.example.com/privkey.pem;

    # Security headers
    add_header Strict-Transport-Security "max-age=63072000" always;
    add_header X-Content-Type-Options nosniff;
    add_header X-Frame-Options DENY;
    add_header X-XSS-Protection "1; mode=block";

    # REST API and static files
    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        # Large body for encrypted text payloads
        client_max_body_size 20M;
    }

    # WebSocket
    location /ws/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_read_timeout 86400;
    }
}

server {
    listen 80;
    server_name clipboard.example.com;
    return 301 https://$server_name$request_uri;
}
```

### 4. SSL Certificate

```bash
# Install certbot
sudo apt-get install certbot python3-certbot-nginx

# Get certificate
sudo certbot --nginx -d clipboard.example.com

# Auto-renewal is configured automatically
```

## Continuous Integration and Deployment

Two workflows live in `.github/workflows`.

`tests.yml` runs on every pull request and on pushes to `main`. It has three jobs:
`unit tests` (no browsers, around two minutes), `frontend checks` (ESLint and stylelint
over the browser sources, under half a minute) and `browser tests` (installs the
Playwright bundles, then runs the e2e suite, five to seven minutes). The e2e report is
kept as a run artifact when that job fails.

`deploy.yml` is manual only, started from the Actions tab. It runs the unit tests,
builds the image, ships it to the server over ssh, restarts the `app` service and
checks that the service answers. The compose file travels with it; `.env` does not,
because it holds the database passwords and stays on the server.

### One-time setup

1. **Secrets.** Settings, Secrets and variables, Actions, New repository secret. Three
   are needed:

   | Name          | Value                                                         |
   |---------------|---------------------------------------------------------------|
   | `VPS_HOST`    | server address                                                |
   | `VPS_USER`    | ssh user used for the deploy                                  |
   | `VPS_SSH_KEY` | that user's private key, whole file including the header line |

2. **Deploy approval.** Settings, Environments, New environment, name it `production`.
   Adding yourself under Required reviewers makes every deploy wait for a click.

3. **Required checks.** Settings, Rules or Branches, add a rule for `main`, enable
   Require status checks to pass, and select all three of `unit tests`, `frontend
   checks` and `browser tests`. Only an account with the Admin role can do this.

### Notes

The workflow deploys as `root` into `/root`, the same way the previous manual script
did, so an existing server needs no changes. A dedicated account in the `docker` group
would be the better arrangement, since a key restricted to it is easier to rotate than a
root key; switching means changing the two `/root` paths in `deploy.yml` and pointing
`VPS_USER` at the new account. Whatever key goes into `VPS_SSH_KEY` should exist only
for this purpose.

Automating the deploy on merge is deliberately not enabled: it would put the production
key behind every merge. Changing `on: workflow_dispatch` to also trigger on a push to
`main` is a one-line edit once that is a conscious decision.

## Environment Variables

| Variable                 | Required | Default          | Description                    |
|--------------------------|----------|------------------|--------------------------------|
| `DB_HOST`                | Yes      | localhost        | MySQL host                     |
| `DB_PORT`                | No       | 3306             | MySQL port                     |
| `DB_NAME`                | No       | secure_clipboard | Database name                  |
| `DB_USER`                | Yes      | clipboard_user   | Database user                  |
| `DB_PASSWORD`            | Yes      | clipboard_pass   | Database password              |
| `SERVER_PORT`            | No       | 8080             | Application port               |
| `CLIPBOARD_TTL_HOURS`    | No       | 24               | Clipboard lifetime             |
| `CHUNK_SIZE`             | No       | 5242880          | Transfer chunk size (bytes)    |
| `MAX_TEXT_SIZE`          | No       | 10485760         | Max text size (bytes)          |
| `MAX_FILE_SIZE`          | No       | 524288000        | Max file size (bytes)          |
| `SPRING_PROFILES_ACTIVE` | No       | default          | Spring profile                 |
| `CLIPBOARD_ID_LENGTH`    | No       | 8                | Clipboard code length          |
| `CLEANUP_INTERVAL`       | No       | 300              | Cleanup run interval (seconds) |
| `TRANSFER_TIMEOUT`       | No       | 600              | Transfer timeout (seconds)     |

## MySQL Notes

- Uses InnoDB engine with utf8mb4 charset
- Foreign keys with `ON DELETE CASCADE` for clean clipboard deletion
- Persistent Docker volume (`mysql_data`) preserves data across restarts
- For production: configure MySQL `max_allowed_packet` to at least 20MB for large text snapshots
- Consider MySQL replication for high availability

## WebSocket Deployment Considerations

- **Load balancers** must support WebSocket upgrade (sticky sessions or direct pass-through)
- **Nginx** requires `proxy_http_version 1.1` and `Connection "upgrade"` headers
- **Timeouts**: Set `proxy_read_timeout` high enough (e.g., 86400s) to prevent idle disconnects
- **Horizontal scaling**: Current in-memory session management requires all devices of a clipboard to connect to the same server instance. For multi-instance, implement Redis pub/sub for session management.

## Persistence Considerations

- **MySQL**: Primary persistent storage for clipboard metadata, encrypted text, file manifests, and transfer state
- **Temp chunks**: Stored in MySQL LONGBLOB (one chunk at a time per transfer). For production with high transfer volume, consider filesystem or object storage (S3)
- **WebSocket sessions**: In-memory only. Lost on server restart. Clients auto-reconnect.

## Monitoring

Recommended monitoring points:
- MySQL connection pool usage
- Active WebSocket connections
- Transfer success/failure rates
- Cleanup task execution
- Disk usage (MySQL data volume)
- Application logs (`docker compose logs -f app`)
