#!/bin/sh
# Run from project root. Never overwrite an existing set of credentials.
set -eu
umask 077
set -C
{
  printf 'JWT_SECRET_BASE64=%s\n' "$(openssl rand -base64 48)"
  printf 'BOOTSTRAP_ADMIN_PASSWORD=Aa1!%s\n' "$(openssl rand -hex 18)"
  printf 'BOOTSTRAP_ADMIN_EMAIL=admin@hospital.local\n'
} > .env.kr3
