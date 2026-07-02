
#Requires -Version 5.1
<#
.SYNOPSIS
    Analise estrutural de um resultado de benchmark TaxIA.

.DESCRIPTION
    Le um ficheiro JSON de resultado e apresenta um resumo estrutural.
    Nao efectua chamadas externas. Nao modifica nenhum ficheiro.

.PARAMETER ResultFile
    Caminho para o ficheiro JSON de resultado (ex: benchmark\results\local\taxia-benchmark-v1_openai_20260623T150056.json).

.EXAMPLE
    .\scripts\analyse-taxia-benchmark.ps1 -ResultFile benchmark\results\local\taxia-benchmark-v1_openai_20260623T150056.json
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ResultFile
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# -- resolve path (relative to project root when not absolute) ----------------

$projectRoot = Split-Path -Parent $PSScriptRoot

if ([System.IO.Path]::IsPathRooted($ResultFile)) {
    $resolvedPath = $ResultFile
} else {
    $resolvedPath = Join-Path $projectRoot $ResultFile
}

try {
    $resolvedPath = (Resolve-Path -LiteralPath $resolvedPath -ErrorAction Stop).Path
} catch {
    Write-Host "ERRO: Ficheiro nao encontrado em '$resolvedPath'."
    exit 1
}

# -- load JSON ----------------------------------------------------------------

$raw = Get-Content -LiteralPath $resolvedPath -Raw -Encoding UTF8
$result = $raw | ConvertFrom-Json

if ($null -eq $result) {
    Write-Host "ERRO: Ficheiro nao e JSON valido."
    exit 1
}

# -- helpers ------------------------------------------------------------------

function Get-PropSafe([object]$obj, [string]$name) {
    $p = $obj.PSObject.Properties[$name]
    if ($null -eq $p) { return $null }
    return $p.Value
}

function Get-IntSafe([object]$obj, [string]$name) {
    $v = Get-PropSafe $obj $name
    if ($null -eq $v) { return 0 }
    try { return [int]$v } catch { return 0 }
}

function Count-Mojibake([string]$text) {
    if ($null -eq $text -or $text -eq "") { return 0 }
    $count = 0
    foreach ($pattern in @("CÃ", "Ã§", "Ã£", "Âº", "â¬")) {
        if ($text.Contains($pattern)) { $count++ }
    }
    return $count
}

# -- extract top-level fields -------------------------------------------------

$provider         = Get-PropSafe $result "expectedProvider"
$model            = ""
$modelsUsed       = Get-PropSafe $result "modelsUsed"
if ($null -ne $modelsUsed) { $model = $modelsUsed -join ", " }

$benchmarkVersion = Get-PropSafe $result "benchmarkVersion"
$executionId      = Get-PropSafe $result "executionId"
$completed        = Get-PropSafe $result "completed"
$totalCases       = Get-IntSafe  $result "totalCases"
$successfulCases  = Get-IntSafe  $result "successfulCases"
$failedCases      = Get-IntSafe  $result "failedCases"
$totalInput       = Get-IntSafe  $result "totalInputTokens"
$totalOutput      = Get-IntSafe  $result "totalOutputTokens"
$totalDuration    = Get-IntSafe  $result "totalDurationMillis"
$avgDuration      = Get-IntSafe  $result "averageDurationMillis"
$startedAt        = Get-PropSafe $result "startedAt"
$finishedAt       = Get-PropSafe $result "finishedAt"

# -- analyse results array ----------------------------------------------------

$results = Get-PropSafe $result "results"
if ($null -eq $results) { $results = @() }

$emptyAnswers            = 0
$providerDivergent       = 0
$mojibakeCases           = 0
$criticalFailures        = 0
$integrityErrors         = [System.Collections.Generic.List[string]]::new()

# Grounding counters (absent in old results → defaults used)
$noProviderCallCases     = 0
$supportedCases          = 0
$partiallySupportedCases = 0
$insufficientCtxCases    = 0
$humanReviewCases        = 0
$rejectedCases           = 0
$totalUnsupportedClaims  = 0

# Outcome counters (executionOutcome field added in Session 5)
$providerResponseCases   = 0
$safeNoCallCases         = 0
$rejectedProviderCases   = 0
$technicalErrorCases     = 0
$providerMismatchCases   = 0
$structuralErrorCases    = 0

foreach ($r in $results) {
    $rCaseId   = Get-PropSafe $r "caseId"
    $rAnswer   = Get-PropSafe $r "answer"
    $rError    = Get-PropSafe $r "error"
    $rActual   = Get-PropSafe $r "actualProvider"
    $rExpected = Get-PropSafe $r "expectedProvider"
    $rCritical = Get-PropSafe $r "critical_failure"

    # Empty answer on success
    $hasError = ($null -ne $rError -and $rError -ne "")
    if (-not $hasError -and ($null -eq $rAnswer -or $rAnswer -eq "")) {
        $emptyAnswers++
        $integrityErrors.Add("${rCaseId}: sucesso com resposta vazia")
    }

    # Provider divergence: only flag when the provider was actually called.
    # executionOutcome=SAFE_NO_PROVIDER_CALL means actualProvider="none" by design — not a mismatch.
    $rPcDiv = Get-PropSafe $r "providerCalled"
    $rOc    = Get-PropSafe $r "executionOutcome"
    $rProviderWasCalled = ($null -ne $rPcDiv -and $rPcDiv -eq $true) -or
                          ($null -eq $rPcDiv -and $null -ne $rOc -and $rOc -eq "PROVIDER_RESPONSE")
    if ($rProviderWasCalled) {
        if ($null -ne $rActual -and $null -ne $rExpected -and $rActual -ne "" -and $rExpected -ne "") {
            if ($rActual -ne $rExpected) {
                $providerDivergent++
            }
        }
    }

    # Mojibake in answer
    if ($null -ne $rAnswer) {
        $mb = Count-Mojibake $rAnswer
        if ($mb -gt 0) { $mojibakeCases++ }
    }

    # Critical failure flag
    if ($null -ne $rCritical -and "$rCritical" -eq "True") {
        $criticalFailures++
    }

    # Grounding metrics (backward-compatible: missing fields skipped)
    $rPc = Get-PropSafe $r "providerCalled"
    if ($null -ne $rPc -and $rPc -eq $false) { $noProviderCallCases++ }

    $rSs = Get-PropSafe $r "supportStatus"
    if ($null -ne $rSs) {
        if ($rSs -eq "SUPPORTED")             { $supportedCases++ }
        if ($rSs -eq "PARTIALLY_SUPPORTED")   { $partiallySupportedCases++ }
        if ($rSs -eq "INSUFFICIENT_CONTEXT")  { $insufficientCtxCases++ }
    }

    $rRhv = Get-PropSafe $r "requiresHumanValidation_grounding"
    if ($null -ne $rRhv -and $rRhv -eq $true)  { $humanReviewCases++ }
    if ($null -ne $rSs -and $rSs -eq "REQUIRES_HUMAN_REVIEW" -and ($null -eq $rRhv -or $rRhv -ne $true)) { $humanReviewCases++ }

    $rRr = Get-PropSafe $r "responseRejected"
    if ($null -ne $rRr -and $rRr -eq $true) { $rejectedCases++ }

    $totalUnsupportedClaims += Get-IntSafe $r "unsupportedClaimsCount"

    # Outcome breakdown (executionOutcome field — absent in old results, skipped)
    $rOcField = Get-PropSafe $r "executionOutcome"
    if ($null -ne $rOcField) {
        if ($rOcField -eq "PROVIDER_RESPONSE")         { $providerResponseCases++ }
        if ($rOcField -eq "SAFE_NO_PROVIDER_CALL")     { $safeNoCallCases++ }
        if ($rOcField -eq "REJECTED_PROVIDER_RESPONSE") { $rejectedProviderCases++ }
        if ($rOcField -eq "TECHNICAL_ERROR")           { $technicalErrorCases++ }
        if ($rOcField -eq "PROVIDER_MISMATCH")         { $providerMismatchCases++ }
        if ($rOcField -eq "STRUCTURAL_ERROR")          { $structuralErrorCases++ }
    }
}

# -- integrity checks ---------------------------------------------------------

$integrityOk = $true

if (($successfulCases + $failedCases) -ne $totalCases -and $totalCases -gt 0) {
    $integrityErrors.Add("successfulCases ($successfulCases) + failedCases ($failedCases) != totalCases ($totalCases)")
    $integrityOk = $false
}

if ($emptyAnswers -gt 0) { $integrityOk = $false }
if ($mojibakeCases -gt 0) { $integrityOk = $false }

# -- print report -------------------------------------------------------------

Write-Host ""
Write-Host "Analise do Benchmark TaxIA"
Write-Host "=========================="
Write-Host ""
Write-Host "Ficheiro         : $resolvedPath"
if ($null -ne $executionId -and $executionId -ne "") {
    Write-Host "Execution ID     : $executionId"
}
Write-Host "Versao           : $benchmarkVersion"
Write-Host "Provider         : $provider"
if ($model -ne "") {
    Write-Host "Modelo(s)        : $model"
}
Write-Host "Inicio           : $startedAt"
Write-Host "Fim              : $finishedAt"
Write-Host "Completo         : $completed"
Write-Host ""
Write-Host "--- Execucao ---"
Write-Host "Casos executados : $totalCases"
Write-Host "Sucessos tecn.   : $successfulCases"
Write-Host "Falhas tecnicas  : $failedCases"
Write-Host ""
Write-Host "--- Tokens ---"
Write-Host "Input total      : $totalInput"
Write-Host "Output total     : $totalOutput"
Write-Host ""
Write-Host "--- Duracao ---"
Write-Host "Total (ms)       : $totalDuration"
Write-Host "Media por caso   : $avgDuration ms"
Write-Host ""
Write-Host "--- Integridade ---"
Write-Host "Respostas vazias         : $emptyAnswers"
Write-Host "Provider divergente      : $providerDivergent"
Write-Host "Casos com mojibake       : $mojibakeCases"
Write-Host "Casos falha critica      : $criticalFailures"
Write-Host ""
Write-Host "--- Grounding (politica de resposta fundamentada) ---"
Write-Host "Sem chamada ao provider  : $noProviderCallCases"
Write-Host "Suportados               : $supportedCases"
Write-Host "Parcialmente suportados  : $partiallySupportedCases"
Write-Host "Contexto insuficiente    : $insufficientCtxCases"
Write-Host "Exigem revisao humana    : $humanReviewCases"
Write-Host "Respostas rejeitadas     : $rejectedCases"
Write-Host "Afirmacoes nao suportadas: $totalUnsupportedClaims"
Write-Host ""
Write-Host "--- Outcome da execucao (executionOutcome) ---"
Write-Host "PROVIDER_RESPONSE        : $providerResponseCases"
Write-Host "SAFE_NO_PROVIDER_CALL    : $safeNoCallCases"
Write-Host "REJECTED_PROVIDER_RESPONSE: $rejectedProviderCases"
Write-Host "TECHNICAL_ERROR          : $technicalErrorCases"
Write-Host "PROVIDER_MISMATCH        : $providerMismatchCases"
Write-Host "STRUCTURAL_ERROR         : $structuralErrorCases"

if ($integrityErrors.Count -gt 0) {
    Write-Host ""
    Write-Host "AVISOS DE INTEGRIDADE:"
    foreach ($err in $integrityErrors) {
        Write-Host "  - $err"
    }
}

Write-Host ""
if ($integrityOk) {
    Write-Host "Integridade: OK"
} else {
    Write-Host "Integridade: PROBLEMAS DETECTADOS (ver avisos acima)"
}
Write-Host ""
