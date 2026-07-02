# verify-taxia-backup.ps1
# Verifica a integridade de um backup TaxIA:
#   1. Recalcula SHA-256 e compara com o ficheiro .sha256
#   2. Valida o arquivo com pg_restore --list
#
# Variáveis de ambiente:
#   TAXIA_DB_DOCKER_CONTAINER  (se definido, usa docker exec para pg_restore --list)
#
# Uso:
#   .\verify-taxia-backup.ps1 -DumpFile <caminho>.dump

param(
    [Parameter(Mandatory = $true)]
    [string]$DumpFile
)

Set-StrictMode -Version Latest
# Nao usar Stop: docker exec pg_restore em arquivo invalido escreve no stderr
# e o PS 5.1 trata-o como NativeCommandError, terminando antes do exit 4.
$ErrorActionPreference = "Continue"

$DOCKER_CTR = $env:TAXIA_DB_DOCKER_CONTAINER

$LogDir  = Join-Path (Resolve-Path (Join-Path $PSScriptRoot "../../..")) "logs"
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
$LogFile = Join-Path $LogDir "verify-$(Get-Date -Format 'yyyyMMdd-HHmmss').log"

function Log($msg) {
    $line = "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') $msg"
    Write-Host $line
    Add-Content -Path $LogFile -Value $line
}

Log "=== TaxIA Verify Backup ==="
Log "Arquivo: $DumpFile"

# 1. Ficheiro existe
if (-not (Test-Path $DumpFile)) {
    Log "ERRO: ficheiro nao encontrado: $DumpFile"
    exit 1
}
$DumpFile = Resolve-Path $DumpFile

$Sha256File = "$DumpFile.sha256"
if (-not (Test-Path $Sha256File)) {
    Log "ERRO: ficheiro de checksum nao encontrado: $Sha256File"
    exit 2
}

# 2. Recalcular e comparar SHA-256
Log "A verificar SHA-256..."
$StoredLine = Get-Content -Path $Sha256File -Raw
$StoredHash = ($StoredLine.Trim() -split '\s+')[0].ToLower()
$ActualHash = (Get-FileHash -Path $DumpFile -Algorithm SHA256).Hash.ToLower()

if ($ActualHash -ne $StoredHash) {
    Log "ERRO: checksum invalido!"
    Log "  esperado : $StoredHash"
    Log "  calculado: $ActualHash"
    exit 3
}
Log "SHA-256 OK: $ActualHash"

# 3. Validar arquivo com pg_restore --list
Log "A validar arquivo (pg_restore --list)..."
if ($DOCKER_CTR) {
    $Timestamp   = Get-Date -Format "yyyyMMddHHmmss"
    $ContainerPath = "/tmp/taxia-verify-$Timestamp.dump"
    & docker cp $DumpFile "${DOCKER_CTR}:${ContainerPath}" 2>&1 | Out-Null
    $listOutput = & docker exec $DOCKER_CTR pg_restore --list $ContainerPath 2>$null
    & docker exec $DOCKER_CTR rm -f $ContainerPath 2>&1 | Out-Null
} else {
    $listOutput = & pg_restore --list $DumpFile 2>$null
}

if ($LASTEXITCODE -ne 0) {
    Log "ERRO: arquivo invalido ou corrompido."
    exit 4
}

$tableCount = ($listOutput | Where-Object { $_ -match "TABLE DATA" }).Count
Log "Arquivo valido. Tabelas de dados encontradas: $tableCount"

Log "=== Verificacao concluida com sucesso ==="
exit 0
