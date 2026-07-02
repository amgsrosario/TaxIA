# test-backup-scripts.ps1
# Testes de validacao dos scripts de backup/restore.
# Nao requer base de dados activa para a maioria dos casos.
#
# Variaveis de ambiente opcionais (para os testes de integracao reais):
#   TAXIA_DB_PASSWORD
#   TAXIA_DB_DOCKER_CONTAINER
#
# Uso: .\test-backup-scripts.ps1

$ErrorActionPreference = "Continue"
$TestPassed = 0
$TestFailed = 0

function Pass([string]$name) {
    Write-Host "  PASS  $name" -ForegroundColor Green
    $script:TestPassed++
}

function Fail([string]$name, [string]$detail = "") {
    $msg = "  FAIL  $name"
    if ($detail) { $msg += ": $detail" }
    Write-Host $msg -ForegroundColor Red
    $script:TestFailed++
}

function Assert([bool]$condition, [string]$name, [string]$detail = "") {
    if ($condition) { Pass $name } else { Fail $name $detail }
}

$ScriptDir   = $PSScriptRoot
$ProjectRoot = Resolve-Path (Join-Path $ScriptDir "../../..")
$TempDir     = Join-Path $env:TEMP "taxia-test-$(Get-Date -Format 'yyyyMMddHHmmss')"
New-Item -ItemType Directory -Force -Path $TempDir | Out-Null

# Capturar antes de qualquer teste modificar/remover estas variaveis
$realPass   = $env:TAXIA_DB_PASSWORD
$realDocker = $env:TAXIA_DB_DOCKER_CONTAINER

Write-Host ""
Write-Host "=== Testes dos Scripts de Backup/Restore ===" -ForegroundColor Cyan

# ---------------------------------------------------------------------------
# Bloco 1 - verify-taxia-backup.ps1
# ---------------------------------------------------------------------------
Write-Host ""
Write-Host "--- verify-taxia-backup.ps1 ---"

# T01: arquivo inexistente -> exit 1
& "$ScriptDir\verify-taxia-backup.ps1" -DumpFile "$TempDir\nao-existe.dump" 2>$null | Out-Null
Assert ($LASTEXITCODE -eq 1) "T01: arquivo inexistente retorna exit 1"

# T02: arquivo sem .sha256 -> exit 2
$fakeDump = Join-Path $TempDir "sem-checksum.dump"
Set-Content $fakeDump -Value "fake" -Encoding UTF8
& "$ScriptDir\verify-taxia-backup.ps1" -DumpFile $fakeDump 2>$null | Out-Null
Assert ($LASTEXITCODE -eq 2) "T02: sem ficheiro sha256 retorna exit 2"

# T03: checksum invalido -> exit 3
$dumpBad = Join-Path $TempDir "checksum-invalido.dump"
Set-Content $dumpBad -Value "conteudo" -Encoding UTF8
Set-Content "$dumpBad.sha256" -Value "aabbccddeeff0011223344556677889900112233445566778899001122334455  checksum-invalido.dump" -Encoding UTF8
& "$ScriptDir\verify-taxia-backup.ps1" -DumpFile $dumpBad 2>$null | Out-Null
Assert ($LASTEXITCODE -eq 3) "T03: checksum invalido retorna exit 3"

# T04: SHA-256 correcto mas formato nao e pg_dump -> exit 4 (sem docker) ou 0 (com docker+pg_restore)
$dumpOk = Join-Path $TempDir "sha256-ok.dump"
Set-Content $dumpOk -Value "conteudo de teste" -Encoding UTF8
$okHash  = (Get-FileHash -Path $dumpOk -Algorithm SHA256).Hash.ToLower()
Set-Content "$dumpOk.sha256" -Value "$okHash  sha256-ok.dump" -Encoding UTF8
& "$ScriptDir\verify-taxia-backup.ps1" -DumpFile $dumpOk 2>$null | Out-Null
Assert ($LASTEXITCODE -in @(0,4)) "T04: SHA-256 correcto, formato invalido: retorna 0 ou 4"

# ---------------------------------------------------------------------------
# Bloco 2 - restore-taxia.ps1
# ---------------------------------------------------------------------------
Write-Host ""
Write-Host "--- restore-taxia.ps1 ---"

# T05: dump inexistente -> exit 2
$env:TAXIA_DB_PASSWORD = "qualquer"
& "$ScriptDir\restore-taxia.ps1" -DumpFile "$TempDir\nao-existe.dump" -TargetDb "qualquer" 2>$null | Out-Null
Assert ($LASTEXITCODE -eq 2) "T05: dump inexistente retorna exit 2"
Remove-Item Env:\TAXIA_DB_PASSWORD -ErrorAction SilentlyContinue

# T06: sem password -> exit 1
Remove-Item Env:\TAXIA_DB_PASSWORD -ErrorAction SilentlyContinue
& "$ScriptDir\restore-taxia.ps1" -DumpFile "$TempDir\nao-existe.dump" -TargetDb "qualquer" 2>$null | Out-Null
Assert ($LASTEXITCODE -eq 1) "T06: sem TAXIA_DB_PASSWORD retorna exit 1"

# T07: target == source sem -Force -> exit 10
$env:TAXIA_DB_PASSWORD = "qualquer"
& "$ScriptDir\restore-taxia.ps1" -DumpFile "$TempDir\nao-existe.dump" -TargetDb "knowledgeflow" 2>$null | Out-Null
Assert ($LASTEXITCODE -eq 10) "T07: target==source sem -Force retorna exit 10"
Remove-Item Env:\TAXIA_DB_PASSWORD -ErrorAction SilentlyContinue

# T08: logs de restore nao contem password
$latestLog = Get-ChildItem (Join-Path $ProjectRoot "logs") -Filter "restore-*.log" -ErrorAction SilentlyContinue |
             Sort-Object LastWriteTime -Descending | Select-Object -First 1
if ($latestLog) {
    $content = Get-Content $latestLog.FullName -Raw -ErrorAction SilentlyContinue
    Assert ($content -notmatch "PGPASSWORD") "T08: log nao contem PGPASSWORD"
} else {
    Pass "T08: (sem logs de restore para verificar)"
}

# ---------------------------------------------------------------------------
# Bloco 3 - backup-taxia.ps1
# ---------------------------------------------------------------------------
Write-Host ""
Write-Host "--- backup-taxia.ps1 ---"

# T09: sem TAXIA_DB_PASSWORD -> exit 1
Remove-Item Env:\TAXIA_DB_PASSWORD -ErrorAction SilentlyContinue
& "$ScriptDir\backup-taxia.ps1" -OutputDir $TempDir 2>$null | Out-Null
Assert ($LASTEXITCODE -eq 1) "T09: sem TAXIA_DB_PASSWORD retorna exit 1"

# ---------------------------------------------------------------------------
# Bloco 4 - Integracao real (apenas se docker e password disponiveis)
# ---------------------------------------------------------------------------
Write-Host ""
Write-Host "--- Integracao real (docker) ---"

if ($realPass -and $realDocker) {
    # Restaurar variaveis de ambiente limpas pelos testes anteriores
    $env:TAXIA_DB_PASSWORD         = $realPass
    $env:TAXIA_DB_DOCKER_CONTAINER = $realDocker

    # T10: backup real cria ficheiro
    $backupOut = Join-Path $TempDir "integ"
    New-Item -ItemType Directory -Force -Path $backupOut | Out-Null
    & "$ScriptDir\backup-taxia.ps1" -OutputDir $backupOut -Label "test-integ" 2>$null | Out-Null
    $dumpFiles = @(Get-ChildItem $backupOut -Filter "*.dump" -ErrorAction SilentlyContinue)
    Assert ($dumpFiles.Count -eq 1) "T10: backup real cria 1 ficheiro .dump"

    if ($dumpFiles.Count -eq 1) {
        $realDump = $dumpFiles[0].FullName

        # T11: .sha256 criado
        Assert (Test-Path "$realDump.sha256") "T11: ficheiro sha256 criado"

        # T12: verificacao passa
        & "$ScriptDir\verify-taxia-backup.ps1" -DumpFile $realDump 2>$null | Out-Null
        Assert ($LASTEXITCODE -eq 0) "T12: verify-taxia-backup retorna exit 0"

        # T13: restauro para base temporaria
        $env:TAXIA_DB_PASSWORD = $realPass
        $env:TAXIA_DB_DOCKER_CONTAINER = $realDocker
        & "$ScriptDir\restore-taxia.ps1" -DumpFile $realDump -TargetDb "taxia_test_integ" -DropTarget 2>$null | Out-Null
        Assert ($LASTEXITCODE -eq 0) "T13: restauro para taxia_test_integ retorna exit 0"

        # T14: migracoes na base restaurada
        $migRaw = & docker exec -e "PGPASSWORD=$realPass" $realDocker `
            psql -h localhost -U knowledgeflow -d taxia_test_integ -t `
            -c "SELECT COUNT(*) FROM flyway_schema_history WHERE success = true" 2>$null
        $migStr = ($migRaw -join '') -replace '\s',''
        Assert ($migStr -match '^\d+$' -and [int]$migStr -gt 0) "T14: base restaurada tem migracoes Flyway"

        # T15: eliminar base temporaria
        & docker exec -e "PGPASSWORD=$realPass" $realDocker `
            psql -h localhost -U knowledgeflow -d postgres `
            -c "DROP DATABASE IF EXISTS taxia_test_integ" 2>$null | Out-Null
        $stillExists = & docker exec -e "PGPASSWORD=$realPass" $realDocker `
            psql -h localhost -U knowledgeflow -d postgres -t `
            -c "SELECT 1 FROM pg_database WHERE datname = 'taxia_test_integ'" 2>$null
        $existsStr = ($stillExists -join '') -replace '\s',''
        Assert ($existsStr -ne "1") "T15: base temporaria eliminada"

        # T16: base original intacta
        $origMig = & docker exec -e "PGPASSWORD=$realPass" $realDocker `
            psql -h localhost -U knowledgeflow -d knowledgeflow -t `
            -c "SELECT COUNT(*) FROM flyway_schema_history WHERE success = true" 2>$null
        $origStr = ($origMig -join '') -replace '\s',''
        Assert ($origStr -match '^\d+$' -and [int]$origStr -gt 0) "T16: base original intacta"
    }
} else {
    Write-Host "  SKIP  T10-T16: TAXIA_DB_PASSWORD e TAXIA_DB_DOCKER_CONTAINER nao definidas" `
        -ForegroundColor Yellow
}

# ---------------------------------------------------------------------------
# Resultado final
# ---------------------------------------------------------------------------
Remove-Item $TempDir -Recurse -Force -ErrorAction SilentlyContinue

Write-Host ""
Write-Host "=== Resultado ===" -ForegroundColor Cyan
Write-Host "  PASS: $TestPassed" -ForegroundColor Green
if ($TestFailed -gt 0) {
    Write-Host "  FAIL: $TestFailed" -ForegroundColor Red
    exit 1
} else {
    Write-Host "  FAIL: 0"
    Write-Host ""
    Write-Host "Todos os testes passaram." -ForegroundColor Green
    exit 0
}
