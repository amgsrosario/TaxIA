# backup-taxia.ps1
# Cria um backup da base TaxIA em formato custom (pg_dump) + SHA-256.
#
# Variáveis de ambiente:
#   TAXIA_DB_HOST              (default: localhost)
#   TAXIA_DB_PORT              (default: 15432)
#   TAXIA_DB_NAME              (default: knowledgeflow)
#   TAXIA_DB_USER              (default: knowledgeflow)
#   TAXIA_DB_PASSWORD          (obrigatório)
#   TAXIA_DB_DOCKER_CONTAINER  (se definido, executa pg_dump via "docker exec")
#
# Uso:
#   $env:TAXIA_DB_PASSWORD = "secret"
#   .\backup-taxia.ps1 [-OutputDir <caminho>] [-Label <etiqueta>]

param(
    [string]$OutputDir = "backups",
    [string]$Label    = ""
)

Set-StrictMode -Version Latest
# Nao usar Stop globalmente: docker exec pode emitir NOTICEs no stderr
# que o PS 5.1 trata como NativeCommandError.
$ErrorActionPreference = "Continue"

$DB_HOST      = if ($env:TAXIA_DB_HOST)              { $env:TAXIA_DB_HOST }              else { "localhost" }
$DB_PORT      = if ($env:TAXIA_DB_PORT)              { $env:TAXIA_DB_PORT }              else { "15432" }
$DB_NAME      = if ($env:TAXIA_DB_NAME)              { $env:TAXIA_DB_NAME }              else { "knowledgeflow" }
$DB_USER      = if ($env:TAXIA_DB_USER)              { $env:TAXIA_DB_USER }              else { "knowledgeflow" }
$DB_PASS      = $env:TAXIA_DB_PASSWORD
$DOCKER_CTR   = $env:TAXIA_DB_DOCKER_CONTAINER       # ex: "knowledgeflow-db-1"

if (-not $DB_PASS) {
    Write-Host "ERRO: TAXIA_DB_PASSWORD nao definida. Abortando."
    exit 1
}

$ProjectRoot = Resolve-Path (Join-Path $PSScriptRoot "../../..")
# Join-Path no PS 5.1 nao trata caminhos absolutos correctamente; usar Path.Combine
$BackupDir   = [System.IO.Path]::Combine($ProjectRoot, $OutputDir)
$LogDir      = Join-Path $ProjectRoot "logs"
New-Item -ItemType Directory -Force -Path $BackupDir, $LogDir | Out-Null

$Timestamp  = Get-Date -Format "yyyyMMdd-HHmmss"
$Suffix     = if ($Label) { "-$Label" } else { "" }
$DumpFile   = Join-Path $BackupDir "taxia-${Timestamp}${Suffix}.dump"
$Sha256File = "$DumpFile.sha256"
$LogFile    = Join-Path $LogDir "backup-${Timestamp}.log"

function Log($msg) {
    $line = "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') $msg"
    Write-Host $line
    Add-Content -Path $LogFile -Value $line
}

function Invoke-Psql([string]$Query) {
    if ($DOCKER_CTR) {
        return & docker exec -e "PGPASSWORD=$DB_PASS" $DOCKER_CTR `
            psql -h localhost -U $DB_USER -d $DB_NAME -t -c $Query 2>&1
    } else {
        $env:PGPASSWORD = $DB_PASS
        return & psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME -t -c $Query 2>&1
    }
}

$DockerLabel = if ($DOCKER_CTR) { $DOCKER_CTR } else { "local" }
Log "=== TaxIA Backup iniciado ==="
Log "host=$DB_HOST port=$DB_PORT db=$DB_NAME user=$DB_USER docker=$DockerLabel"
Log "destino=$DumpFile"

$env:PGPASSWORD = $DB_PASS
try {
    # ------------------------------------------------------------------
    # Verificar conectividade
    # ------------------------------------------------------------------
    Log "A verificar conectividade..."
    if ($DOCKER_CTR) {
        $pingOut = & docker exec $DOCKER_CTR pg_isready -U $DB_USER -d $DB_NAME 2>&1
        if ($LASTEXITCODE -ne 0) { Log "ERRO: inacessivel. $pingOut"; exit 2 }
    } else {
        $pingOut = & pg_isready -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME 2>&1
        if ($LASTEXITCODE -ne 0) { Log "ERRO: inacessivel. $pingOut"; exit 2 }
    }
    Log "Conectividade OK."

    # ------------------------------------------------------------------
    # Verificar que a base não está vazia
    # ------------------------------------------------------------------
    Log "A verificar que a base nao esta vazia..."
    $countRaw = Invoke-Psql "SELECT COUNT(*) FROM flyway_schema_history WHERE success = true"
    if ($LASTEXITCODE -ne 0) {
        Log "ERRO: nao foi possivel verificar flyway_schema_history. $countRaw"
        exit 3
    }
    $migCount = [int](($countRaw | Where-Object { $_ -match '\d' } | Select-Object -First 1) -replace '\s','')
    if ($migCount -eq 0) {
        Log "ERRO: base de dados vazia (0 migracoes). Backup recusado."
        exit 4
    }
    Log "Base verificada: $migCount migracoes encontradas."

    # ------------------------------------------------------------------
    # Criar backup
    # ------------------------------------------------------------------
    Log "A criar backup (formato custom)..."
    if ($DOCKER_CTR) {
        # Dump dentro do container, depois copiar para o host
        $ContainerDump = "/tmp/taxia-backup-${Timestamp}.dump"
        & docker exec -e "PGPASSWORD=$DB_PASS" $DOCKER_CTR `
            pg_dump --format=custom --no-password `
                    --username=$DB_USER --file=$ContainerDump $DB_NAME 2>&1 | ForEach-Object { Log $_ }
        if ($LASTEXITCODE -ne 0) { Log "ERRO: pg_dump falhou (docker)"; exit 5 }
        & docker cp "${DOCKER_CTR}:${ContainerDump}" $DumpFile 2>&1 | ForEach-Object { Log $_ }
        if ($LASTEXITCODE -ne 0) { Log "ERRO: docker cp falhou"; exit 6 }
        & docker exec $DOCKER_CTR rm -f $ContainerDump 2>&1 | Out-Null
    } else {
        & pg_dump --format=custom --no-password `
                  --host=$DB_HOST --port=$DB_PORT --username=$DB_USER `
                  --file=$DumpFile $DB_NAME 2>&1 | ForEach-Object { Log $_ }
        if ($LASTEXITCODE -ne 0) { Log "ERRO: pg_dump falhou"; exit 5 }
    }

    if (-not (Test-Path $DumpFile)) {
        Log "ERRO: ficheiro de backup nao foi criado."
        exit 6
    }

    $FileSize = (Get-Item $DumpFile).Length
    Log "Backup criado: $DumpFile ($FileSize bytes)"

    # ------------------------------------------------------------------
    # SHA-256
    # ------------------------------------------------------------------
    Log "A calcular SHA-256..."
    $Hash = (Get-FileHash -Path $DumpFile -Algorithm SHA256).Hash.ToLower()
    $DumpBaseName = Split-Path $DumpFile -Leaf
    "$Hash  $DumpBaseName" | Set-Content -Path $Sha256File -Encoding UTF8
    Log "SHA-256: $Hash"
    Log "Checksum gravado em: $Sha256File"

    # ------------------------------------------------------------------
    # Validar arquivo (pg_restore --list)
    # ------------------------------------------------------------------
    Log "A validar arquivo com pg_restore --list..."
    if ($DOCKER_CTR) {
        # Copiar dump para o container só para validar e depois apagar
        $ContainerCheck = "/tmp/taxia-check-${Timestamp}.dump"
        & docker cp $DumpFile "${DOCKER_CTR}:${ContainerCheck}" 2>&1 | Out-Null
        $listOutput = & docker exec $DOCKER_CTR pg_restore --list $ContainerCheck 2>&1
        & docker exec $DOCKER_CTR rm -f $ContainerCheck 2>&1 | Out-Null
    } else {
        $listOutput = & pg_restore --list $DumpFile 2>&1
    }
    if ($LASTEXITCODE -ne 0) {
        Log "ERRO: arquivo invalido (pg_restore --list falhou)."
        exit 7
    }
    $tableCount = ($listOutput | Where-Object { $_ -match "TABLE DATA" }).Count
    Log "Arquivo valido. Tabelas encontradas: $tableCount"

    Log "=== Backup concluido com sucesso ==="
    Log "Ficheiro: $DumpFile"
    Log "SHA-256:  $Sha256File"
    exit 0

} finally {
    Remove-Item Env:\PGPASSWORD -ErrorAction SilentlyContinue
}
