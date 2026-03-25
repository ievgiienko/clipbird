#!/usr/bin/env bash
# Generates a .env file with random database passwords.
# Run once before first `docker compose up`.
# Will NOT overwrite an existing .env file unless --force is passed.

set -euo pipefail

ENV_FILE=".env"

if [ -f "$ENV_FILE" ] && [ "${1:-}" != "--force" ]; then
    echo ".env already exists. Use --force to regenerate."
    exit 1
fi

gen_password() {
    head -c 32 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 32
}

ROOT_PASS=$(gen_password)
DB_PASS=$(gen_password)

cat > "$ENV_FILE" <<EOF
# MySQL
MYSQL_ROOT_PASSWORD=${ROOT_PASS}
DB_NAME=secure_clipboard
DB_USER=clipboard_user
DB_PASSWORD=${DB_PASS}
MYSQL_PORT=3306

# Redis
REDIS_HOST=redis
REDIS_PORT=6379

# Application
SERVER_PORT=8080
CLIPBOARD_TTL_HOURS=24
CHUNK_SIZE=1048576
MAX_TEXT_SIZE=10485760
MAX_FILE_SIZE=524288000

# Spring profile (default, prod)
SPRING_PROFILES_ACTIVE=default
EOF

echo ".env created with random passwords."
