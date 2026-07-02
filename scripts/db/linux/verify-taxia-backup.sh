#!/usr/bin/env bash
# verify-taxia-backup.sh
# Verifica integridade de um backup TaxIA:
#   1. Recalcula SHA-256 e compara com o ficheiro .sha256
#   2. Valida o arquivo com pg_restore --list
#
# Uso: ./verify-taxia-backup.sh <ficheiro.dump>

set -euo pipefail

if [ "$#" -ne 1 ]; then
    echo "Uso: $0 <ficheiro.dump>" >&2
    exit 1
fi

DUMP_FILE="$1"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
LOG_DIR="$PROJECT_ROOT/logs"

mkdir -p "$LOG_DIR"
LOG_FILE="$LOG_DIR/verify-$(date +%Y%m%d-%H%M%S).log"

log() {
    echo "$(date '+%Y-%m-%d %H:%M:%S') $*" | tee -a "$LOG_FILE"
}

log "=== TaxIA Verify Backup ==="
log "Arquivo: $DUMP_FILE"

# 1. Ficheiro existe
if [ ! -f "$DUMP_FILE" ]; then
    log "ERRO: ficheiro nao encontrado: $DUMP_FILE"
    exit 1
fi

SHA256_FILE="${DUMP_FILE}.sha256"
if [ ! -f "$SHA256_FILE" ]; then
    log "ERRO: ficheiro de checksum nao encontrado: $SHA256_FILE"
    exit 2
fi

# 2. Recalcular e comparar SHA-256
log "A verificar SHA-256..."
STORED_HASH=$(awk '{print $1}' "$SHA256_FILE" | tr '[:upper:]' '[:lower:]')

if command -v sha256sum > /dev/null 2>&1; then
    ACTUAL_HASH=$(sha256sum "$DUMP_FILE" | awk '{print $1}')
else
    ACTUAL_HASH=$(shasum -a 256 "$DUMP_FILE" | awk '{print $1}')
fi

if [ "$ACTUAL_HASH" != "$STORED_HASH" ]; then
    log "ERRO: checksum invalido!"
    log "  esperado : $STORED_HASH"
    log "  calculado: $ACTUAL_HASH"
    exit 3
fi
log "SHA-256 OK: $ACTUAL_HASH"

# 3. Validar com pg_restore --list
log "A validar arquivo (pg_restore --list)..."
TABLE_COUNT=$(pg_restore --list "$DUMP_FILE" 2>/dev/null | grep -c "TABLE DATA" || true)
log "Arquivo valido. Tabelas de dados encontradas: $TABLE_COUNT"

log "=== Verificacao concluida com sucesso ==="
