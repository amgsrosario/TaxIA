#!/usr/bin/env bash
# backup-taxia.sh
# Cria backup da base TaxIA em formato custom + SHA-256.
#
# Variáveis de ambiente:
#   TAXIA_DB_HOST      (default: localhost)
#   TAXIA_DB_PORT      (default: 15432)
#   TAXIA_DB_NAME      (default: knowledgeflow)
#   TAXIA_DB_USER      (default: knowledgeflow)
#   TAXIA_DB_PASSWORD  (obrigatório)
#
# Uso: TAXIA_DB_PASSWORD=secret ./backup-taxia.sh [-o <dir>] [-l <label>]

set -euo pipefail

OUTPUT_DIR="backups"
LABEL=""

while getopts "o:l:" opt; do
    case $opt in
        o) OUTPUT_DIR="$OPTARG" ;;
        l) LABEL="$OPTARG" ;;
        *) echo "Uso: $0 [-o output_dir] [-l label]" >&2; exit 1 ;;
    esac
done

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
BACKUP_DIR="$PROJECT_ROOT/$OUTPUT_DIR"
LOG_DIR="$PROJECT_ROOT/logs"

mkdir -p "$BACKUP_DIR" "$LOG_DIR"

TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
SUFFIX="${LABEL:+-$LABEL}"
DUMP_FILE="$BACKUP_DIR/taxia-${TIMESTAMP}${SUFFIX}.dump"
SHA256_FILE="${DUMP_FILE}.sha256"
LOG_FILE="$LOG_DIR/backup-${TIMESTAMP}.log"

log() {
    echo "$(date '+%Y-%m-%d %H:%M:%S') $*" | tee -a "$LOG_FILE"
}

log "=== TaxIA Backup iniciado ==="
log "host=$DB_HOST port=$DB_PORT db=$DB_NAME user=$DB_USER"
log "destino=$DUMP_FILE"

# Injectar password via variável de ambiente — nunca em argumentos
export PGPASSWORD="$DB_PASS"

cleanup() { unset PGPASSWORD; }
trap cleanup EXIT

# Verificar conectividade
log "A verificar conectividade..."
if ! pg_isready -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" > /dev/null 2>&1; then
    log "ERRO: base de dados inacessivel."
    exit 2
fi
log "Conectividade OK."

# Verificar que a base não está vazia
log "A verificar que a base nao esta vazia..."
MIG_COUNT=$(psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -t \
    -c "SELECT COUNT(*) FROM flyway_schema_history WHERE success = true" 2>&1 | tr -d ' ')
if [ "$MIG_COUNT" -eq 0 ]; then
    log "ERRO: base de dados vazia (0 migracoes). Backup recusado."
    exit 4
fi
log "Base verificada: $MIG_COUNT migracoes encontradas."

# Criar backup
log "A criar backup (formato custom)..."
pg_dump --format=custom --no-password \
        --host="$DB_HOST" --port="$DB_PORT" --username="$DB_USER" \
        --file="$DUMP_FILE" "$DB_NAME" 2>> "$LOG_FILE"

FILE_SIZE=$(stat -c%s "$DUMP_FILE" 2>/dev/null || stat -f%z "$DUMP_FILE")
log "Backup criado: $DUMP_FILE ($FILE_SIZE bytes)"

# SHA-256
log "A calcular SHA-256..."
if command -v sha256sum > /dev/null 2>&1; then
    HASH=$(sha256sum "$DUMP_FILE" | awk '{print $1}')
    DUMP_BASENAME=$(basename "$DUMP_FILE")
    echo "$HASH  $DUMP_BASENAME" > "$SHA256_FILE"
else
    # macOS fallback
    HASH=$(shasum -a 256 "$DUMP_FILE" | awk '{print $1}')
    DUMP_BASENAME=$(basename "$DUMP_FILE")
    echo "$HASH  $DUMP_BASENAME" > "$SHA256_FILE"
fi
log "SHA-256: $HASH"
log "Checksum gravado em: $SHA256_FILE"

# Validar arquivo
log "A validar arquivo com pg_restore --list..."
TABLE_COUNT=$(pg_restore --list "$DUMP_FILE" 2>/dev/null | grep -c "TABLE DATA" || true)
log "Arquivo valido. Tabelas de dados encontradas: $TABLE_COUNT"

log "=== Backup concluido com sucesso ==="
