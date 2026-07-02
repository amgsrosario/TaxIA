# restore-taxia.ps1
# Restaura um backup TaxIA para uma base de dados de destino.
# Recusa por defeito restaurar sobre a base de origem (requer -Force).
#
# Variáveis de ambiente:
#   TAXIA_DB_HOST              (default: localhost)
#   TAXIA_DB_PORT              (default: 15432)
#   TAXIA_DB_NAME              (default: knowledgeflow — base protegida)
#   TAXIA_DB_USER              (default: knowledgeflow)
#   TAXIA_DB_PASSWORD          (obrigatório)
#   TAXIA_DB_DOCKER_CONTAINER  (se definido, executa pg_restore via docker exec)
#
# Uso:
#   $env:TAXIA_DB_PASSWORD = "secret"
#   .\restore-taxia.ps1 -DumpFile taxia.dump -TargetDb taxia_restore_test

param(
    [Parameter(Mandatory = $true)] [string]$DumpFile,
    [Parameter(Mandatory = $true)] [string]$TargetDb,
    [string]$SourceDb  = "",
    [switch]$Force,
    [switch]$DropTarget
)

# Não usar Stop — as chamadas ao docker exec podem emitir NOTICEs no stderr
# que o PowerShell 5.1 trata como NativeCommandError.
$ErrorActionPreference = "Continue"

$DB_HOST    = if ($env:TAXIA_DB_HOST) { $env:TAXIA_DB_HOST } else { "localhost" }
$DB_PORT    = if ($env:TAXIA_DB_PORT) { $env:TAXIA_DB_PORT } else { "15432" }
$DB_USER    = if ($env:TAXIA_DB_USER) { $env:TAXIA_DB_USER } else { "knowledgeflow" }
$DB_PASS    = $env:TAXIA_DB_PASSWORD
$DOCKER_CTR = $env:TAXIA_DB_DOCKER_CONTAINER
$SourceDbName = if ($SourceDb) { $SourceDb } elseif ($env:TAXIA_DB_NAME) { $env:TAXIA_DB_NAME } else { "knowledgeflow" }
$DockerLabel  = if ($DOCKER_CTR) { $DOCKER_CTR } else { "local" }

if (-not $DB_PASS) { Write-Error "TAXIA_DB_PASSWORD nao definida."; exit 1 }

$LogDir  = Join-Path (Resolve-Path (Join-Path $PSScriptRoot "../../..")) "logs"
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
$LogFile = Join-Path $LogDir "restore-$(Get-Date -Format 'yyyyMMdd-HHmmss').log"

function Log($msg) {
    $line = "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') $msg"
    Write-Host $line
    Add-Content -Path $LogFile -Value $line -ErrorAction SilentlyContinue
}

function DockerPsql([string]$Db, [string]$Query) {
    if ($DOCKER_CTR) {
        $out = & docker exec -e "PGPASSWORD=$DB_PASS" $DOCKER_CTR `
            psql -h localhost -U $DB_USER -d $Db -t -c $Query 2>$null
        return $out
    } else {
        $env:PGPASSWORD = $DB_PASS
        $out = & psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $Db -t -c $Query 2>$null
        return $out
    }
}

Log "=== TaxIA Restore iniciado ==="
Log "dump=$DumpFile target=$TargetDb docker=$DockerLabel"

# --------------------------------------------------------------------------
# 1. Protecção anti-sobrescrita
# --------------------------------------------------------------------------
if ($TargetDb -eq $SourceDbName -and -not $Force) {
    Log "ERRO: TargetDb '$TargetDb' = SourceDb '$SourceDbName'. Requer -Force."
    exit 10
}

# --------------------------------------------------------------------------
# 2. Arquivo existe
# --------------------------------------------------------------------------
if (-not (Test-Path $DumpFile)) {
    Log "ERRO: ficheiro nao encontrado: $DumpFile"
    exit 2
}
$DumpFile = (Resolve-Path $DumpFile).Path

# --------------------------------------------------------------------------
# 3. Verificar integridade
# --------------------------------------------------------------------------
Log "A verificar integridade..."
$verifyScript = Join-Path $PSScriptRoot "verify-taxia-backup.ps1"
& $verifyScript -DumpFile $DumpFile
if ($LASTEXITCODE -ne 0) {
    Log "ERRO: verificacao falhou. Restauro abortado."
    exit 3
}
Log "Integridade OK."

$env:PGPASSWORD = $DB_PASS
$Timestamp = Get-Date -Format "yyyyMMddHHmmss"

try {
    # --------------------------------------------------------------------------
    # 4. Gerir base de destino
    # --------------------------------------------------------------------------
    if ($DropTarget) {
        Log "A eliminar '$TargetDb' (--DropTarget)..."
        if ($DOCKER_CTR) {
            & docker exec -e "PGPASSWORD=$DB_PASS" $DOCKER_CTR `
                psql -h localhost -U $DB_USER -d postgres `
                -c "DROP DATABASE IF EXISTS `"$TargetDb`"" 2>$null | Out-Null
        } else {
            & psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d postgres `
                -c "DROP DATABASE IF EXISTS `"$TargetDb`"" 2>$null | Out-Null
        }
        Log "DROP concluido. A criar nova base..."
        # Após DROP forçado, sempre cria de novo
        if ($DOCKER_CTR) {
            & docker exec -e "PGPASSWORD=$DB_PASS" $DOCKER_CTR `
                psql -h localhost -U $DB_USER -d postgres `
                -c "CREATE DATABASE `"$TargetDb`"" 2>$null | Out-Null
            & docker exec -e "PGPASSWORD=$DB_PASS" $DOCKER_CTR `
                psql -h localhost -U $DB_USER -d $TargetDb `
                -c "CREATE EXTENSION IF NOT EXISTS vector" 2>$null | Out-Null
        } else {
            & psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d postgres `
                -c "CREATE DATABASE `"$TargetDb`"" 2>$null | Out-Null
            & psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $TargetDb `
                -c "CREATE EXTENSION IF NOT EXISTS vector" 2>$null | Out-Null
        }
        Log "Base '$TargetDb' recriada."
    } else {
        $existsRaw = DockerPsql "postgres" "SELECT 1 FROM pg_database WHERE datname = '$TargetDb'"
        $dbExists  = ($existsRaw -join '') -replace '\s',''
        if ($dbExists -notmatch "1") {
            Log "A criar base '$TargetDb'..."
            if ($DOCKER_CTR) {
                & docker exec -e "PGPASSWORD=$DB_PASS" $DOCKER_CTR `
                    psql -h localhost -U $DB_USER -d postgres `
                    -c "CREATE DATABASE `"$TargetDb`"" 2>$null | Out-Null
                & docker exec -e "PGPASSWORD=$DB_PASS" $DOCKER_CTR `
                    psql -h localhost -U $DB_USER -d $TargetDb `
                    -c "CREATE EXTENSION IF NOT EXISTS vector" 2>$null | Out-Null
            } else {
                & psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d postgres `
                    -c "CREATE DATABASE `"$TargetDb`"" 2>$null | Out-Null
                & psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $TargetDb `
                    -c "CREATE EXTENSION IF NOT EXISTS vector" 2>$null | Out-Null
            }
            Log "Base '$TargetDb' criada."
        } else {
            Log "Base '$TargetDb' ja existe."
        }
    }

    # --------------------------------------------------------------------------
    # 5. Restaurar
    # --------------------------------------------------------------------------
    Log "A restaurar arquivo para '$TargetDb'..."
    if ($DOCKER_CTR) {
        $ContainerDump = "/tmp/taxia-restore-${Timestamp}.dump"
        & docker cp $DumpFile "${DOCKER_CTR}:${ContainerDump}" 2>$null | Out-Null
        $restoreOut = & docker exec -e "PGPASSWORD=$DB_PASS" $DOCKER_CTR `
            pg_restore --no-password --clean --if-exists --no-owner --no-acl `
                       --username=$DB_USER --dbname=$TargetDb $ContainerDump 2>$null
        $restoreOut | ForEach-Object { if ($_) { Log "  pg_restore: $_" } }
        & docker exec $DOCKER_CTR rm -f $ContainerDump 2>$null | Out-Null
    } else {
        $restoreOut = & pg_restore --no-password --clean --if-exists --no-owner --no-acl `
            --host=$DB_HOST --port=$DB_PORT --username=$DB_USER `
            --dbname=$TargetDb $DumpFile 2>$null
        $restoreOut | ForEach-Object { if ($_) { Log "  pg_restore: $_" } }
    }
    Log "Restauro concluido. A validar..."

    # --------------------------------------------------------------------------
    # 6. Validar restauro
    # --------------------------------------------------------------------------
    $migRaw   = DockerPsql $TargetDb "SELECT COUNT(*) FROM flyway_schema_history WHERE success = true"
    $migStr   = ($migRaw -join '') -replace '\s',''
    if ($migStr -notmatch '^\d+$') {
        Log "ERRO: nao foi possivel verificar flyway_schema_history em '$TargetDb'. Resposta: $migRaw"
        exit 22
    }
    $migCount = [int]$migStr
    if ($migCount -eq 0) {
        Log "ERRO: base restaurada vazia (0 migracoes)."
        exit 21
    }
    Log "Restauro validado: $migCount migracoes em '$TargetDb'."

    Log "=== Restauro concluido com sucesso ==="
    exit 0

} finally {
    Remove-Item Env:\PGPASSWORD -ErrorAction SilentlyContinue
}
