#!/bin/sh
set -eu
mkdir -p /backups
echo "MySQL backup loop started (every ${BACKUP_INTERVAL_SECONDS:-3600}s)"
while true; do
  stamp="$(date -u +%Y%m%dT%H%M%SZ)"
  file="/backups/vms-${stamp}.sql.gz"
  if mysqldump -h"${DB_HOST:-mysql}" -u"${DB_USERNAME:-vms}" -p"${DB_PASSWORD:-vms}" --single-transaction --routines --triggers "${DB_NAME:-vms}" | gzip -c >"$file"; then
    echo "Backup OK: $file"
    # Keep last 14 backups
    ls -1t /backups/vms-*.sql.gz 2>/dev/null | tail -n +15 | xargs -r rm -f
  else
    echo "Backup FAILED at $stamp" >&2
    rm -f "$file"
  fi
  sleep "${BACKUP_INTERVAL_SECONDS:-3600}"
done
