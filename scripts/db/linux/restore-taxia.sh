#!/usr/bin/env bash
# restore-taxia.sh
# Restaura um backup TaxIA para uma base de dados de destino.
# Por defeito recusa restaurar sobre a base de origem.
#
# Variáveis de ambiente:
#   TAXIA_DB_HOST      (default: localhost)
#   TAXIA_DB_PORT      (default: 15432)
#   TAXIA_DB_NAME      (default: knowledgeflow — base de origem protegida)
#   TAXIA_DB_USER      (default: knowledgeflow)
#   TAXIA_DB_PASSWORD  (obrigatório)
#
# Uso:
#   TAXIA_DB_PASSWORD=secret ./restore-taxia.sh -f taxia.dump -t taxia_restore_test
#   TAXIA_DB_PASSWORD=secret ./restore-taxia.sh -f taxia.dump -t knowledgeflow --force

set -euo pipefail

DUMP_FILE=""
TARGET_DB=""
FORCE=false
DROP_TARGET=false

while [[ "$#" -gt 0 ]]; do
    case "$1" in
        -f|--file)     DUMP_FILE="$2"; shift 2 ;;
        -t|--target)   TARGET_DB="$2"; shift 2 ;;
        --force)       FORCE=true; shift ;;
        --drop-target) DROP_TARGET=true; shift ;;
        *) echo "Argumento desconhecido: $1" >&2; exit 1 ;;
    esac
done

if [ -z "$DUMP_FILE" ] || [ -z "$TARGET_DB" ]; then
    echo "Uso: $0 -f <dump> -t <base_destino> [--force] [--drop-target]" >&2
    exit 1
fi

DB_HOST="${TAXIA_DB_HOST:-localhost}"
DB_PORT="${TAXIA_DB_PORT:-15432}"
DB_NAME="${TAXIA_DB_NAME:-knowledgeflow}"
DB_USER="${TAXIA_DB_USER:-knowledgeflow}"
DB_PASS="${TAXIA_DB_PASSWORD:-}"

if [ -z "$DB_PASS" ]; then
    echo "ERRO: TAXIA_DB_PASSWORD nao definida." >&2
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
LOG_DIR="$PROJECT_ROOT/logs"
mkdir -p "$LOG_DIR"
LOG_FILE="$LOG_DIR/restore-$(date +%Y%m%d-%H%M%S).log"

log() {
    echo "$(date '+%Y-%m-%d %H:%M:%S') $*" | tee -a "$LOG_FILE"
}

log "=== TaxIA Restore iniciado ==="
log "dump=$DUMP_FILE target=$TARGET_DB"

# 1. Protecção anti-sobrescrita
if [ "$TARGET_DB" = "$DB_NAME" ] && [ "$FORCE" = false ]; then
    log "ERRO: TargetDb '$TARGET_DB' e igual a SourceDb '$DB_NAME'."
    log "Restauro sobre a base de origem requer --force explícito."
    exit 10
fi

# 2. Arquivo existe
if [ ! -f "$DUMP_FILE" ]; then
    log "ERRO: ficheiro nao encontrado: $DUMP_FILE"
    exit 2
fi

# 3. Verificar integridade
log "A verificar integridade do arquivo..."
SCRIPT_DIR_VERIFY="$(dirname "$0")"
bash "$SCRIPT_DIR_VERIFY/verify-taxia-backup.sh" "$DUMP_FILE"
log "Integridade OK."

export PGPASSWORD="$DB_PASS"
cleanup() { unset PGPASSWORD; }
trap cleanup EXIT

# 4. Gerir base de destino
if [ "$DROP_TARGET" = true ]; then
    log "A eliminar base '$TARGET_DB' (--drop-target activado)..."
    psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d postgres \
         -c "DROP DATABASE IF EXISTS \"$TARGET_DB\"" >> "$LOG_FILE" 2>&1
fi

DB_EXISTS=$(psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d postgres -t \
    -c "SELECT 1 FROM pg_database WHERE datname = '$TARGET_DB'" 2>/dev/null | tr -d ' \n')

if [ "$DB_EXISTS" != "1" ]; then
    log "A criar base '$TARGET_DB'..."
    psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d postgres \
         -c "CREATE DATABASE \"$TARGET_DB\"" >> "$LOG_FILE" 2>&1
    # Instalar pgvector
    psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$TARGET_DB" \
         -c "CREATE EXTENSION IF NOT EXISTS vector" >> "$LOG_FILE" 2>&1
    log "Base '$TARGET_DB' criada."
fi

# 5. Restaurar
log "A restaurar arquivo para '$TARGET_DB'..."
pg_restore --no-password --clean --if-exists --no-owner --no-acl \
           --host="$DB_HOST" --port="$DB_PORT" --username="$DB_USER" \
           --dbname="$TARGET_DB" "$DUMP_FILE" >> "$LOG_FILE" 2>&1 || true

# 6. Validar restauro
log "A validar restauro (flyway_schema_history)..."
MIG_COUNT=$(psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$TARGET_DB" -t \
    -c "SELECT COUNT(*) FROM flyway_schema_history WHERE success = true" 2>/dev/null | tr -d ' ')

if [ "$MIG_COUNT" -eq 0 ]; then
    log "ERRO: base restaurada esta vazia (0 migracoes)."
    exit 21
fi
log "Restauro validado: $MIG_COUNT migracoes em '$TARGET_DB'."

log "=== Restauro concluido com sucesso ==="
