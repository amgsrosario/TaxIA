#Requires -Version 5.1
<#
.SYNOPSIS
    Executor controlado do benchmark TaxIA.

.DESCRIPTION
    Executa os casos do dataset contra a aplicação TaxIA já em funcionamento.
    Não arranca a aplicação, não configura providers e não efectua retries.

    A aplicação deve estar previamente arrancada com um único provider real.
    Cada provider deve ser executado numa sessão separada.

    Confirmação obrigatória: escreve EXECUTAR quando solicitado.
    Máximo de chamadas: exactamente uma por caso seleccionado.

.EXAMPLE
    .\scripts\run-taxia-benchmark.ps1 -Provider openai

.EXAMPLE
    .\scripts\run-taxia-benchmark.ps1 -Provider anthropic

.EXAMPLE
    .\scripts\run-taxia-benchmark.ps1 -Provider openai -CaseId TAXIA-004

.EXAMPLE
    .\scripts\run-taxia-benchmark.ps1 -Provider anthropic -DelaySeconds 3 -OutputDirectory benchmark\results\local
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("anthropic", "openai")]
    [string]$Provider,

    [string]$BaseUrl = "http://localhost:8082",

    [string]$Dataset = "benchmark\cases\taxia-benchmark-v1.json",

    [string]$CaseId = "",

    [int]$DelaySeconds = 2,

    [string]$OutputDirectory = "benchmark\results\local",

    [switch]$ValidateDatasetOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# -- helpers -------------------------------------------------------------------

function Format-IsoTimestamp {
    (Get-Date -Format "yyyy-MM-ddTHH:mm:ss")
}

function Format-FileTimestamp {
    (Get-Date -Format "yyyyMMddTHHmmss")
}

function Escape-CsvField([string]$value) {
    if ($null -eq $value) { return "" }
    # RFC 4180: wrap in quotes if contains comma, quote, newline or semicolon
    if ($value -match '[",;\r\n]') {
        return '"' + $value.Replace('"', '""') + '"'
    }
    return $value
}

function Write-Progress-Line([int]$index, [int]$total, [string]$caseId, [string]$status) {
    Write-Host "[$index/$total] $caseId - $status"
}

# Sends a POST request and decodes the response body explicitly as UTF-8.
# Invoke-RestMethod in PS 5.1 uses the system codepage (Windows-1252) when
# the server omits charset from Content-Type - causing mojibake in Portuguese text.
# GetResponse() throws WebException on non-2xx, compatible with the existing catch block.
function Invoke-JsonPostUtf8 {
    param(
        [string]$Uri,
        [byte[]]$BodyBytes,
        [string]$Authorization = "",
        [int]   $TimeoutSec    = 120
    )
    $req               = [System.Net.HttpWebRequest]::Create($Uri)
    $req.Method        = "POST"
    $req.ContentType   = "application/json; charset=utf-8"
    $req.Timeout       = $TimeoutSec * 1000
    $req.ContentLength = $BodyBytes.Length
    if ($Authorization -ne "") {
        $req.Headers.Add("Authorization", $Authorization)
    }
    $reqStream = $req.GetRequestStream()
    $reqStream.Write($BodyBytes, 0, $BodyBytes.Length)
    $reqStream.Close()
    $resp       = $req.GetResponse()
    $stream     = $resp.GetResponseStream()
    $reader     = [System.IO.StreamReader]::new($stream, [System.Text.Encoding]::UTF8)
    $jsonString = $reader.ReadToEnd()
    $reader.Dispose()
    $resp.Close()
    return $jsonString | ConvertFrom-Json
}

# -- resolve dataset path (always relative to project root, not CWD) ----------

$projectRoot = Split-Path -Parent $PSScriptRoot

if ([System.IO.Path]::IsPathRooted($Dataset)) {
    $datasetPath = $Dataset
} else {
    $datasetPath = Join-Path $projectRoot $Dataset
}

try {
    $datasetPath = (Resolve-Path -LiteralPath $datasetPath -ErrorAction Stop).Path
} catch {
    Write-Host "ERRO: Dataset nao encontrado em '$datasetPath'."
    Write-Host "  Parâmetro recebido : $Dataset"
    Write-Host "  Raiz do projecto   : $projectRoot"
    exit 1
}

# -- load and validate dataset (before backend check, auth or API calls) ------

$datasetJson   = Get-Content -LiteralPath $datasetPath -Raw -Encoding UTF8
$datasetObject = $datasetJson | ConvertFrom-Json

if ($null -eq $datasetObject) {
    Write-Host "Dataset invalido: ficheiro nao e JSON valido."
    exit 1
}

$versionProp = $datasetObject.PSObject.Properties["benchmarkVersion"]
if ($null -eq $versionProp -or [string]::IsNullOrWhiteSpace([string]$versionProp.Value)) {
    Write-Host "Dataset invalido: campo obrigatorio 'benchmarkVersion' ausente ou vazio."
    exit 1
}

$casesProp = $datasetObject.PSObject.Properties["cases"]
if ($null -eq $casesProp -or $null -eq $casesProp.Value -or $casesProp.Value.Count -eq 0) {
    Write-Host "Dataset invalido: campo obrigatorio 'cases' ausente ou vazio."
    exit 1
}

$benchmarkVersion = [string]$versionProp.Value
$allCases         = $casesProp.Value

# -- resolve output directory (relative to project root, not CWD) -------------

if ([System.IO.Path]::IsPathRooted($OutputDirectory)) {
    $resolvedOutputDir = $OutputDirectory
} else {
    $resolvedOutputDir = Join-Path $projectRoot $OutputDirectory
}

# -- ValidateDatasetOnly mode: exit after dataset validation ------------------

if ($ValidateDatasetOnly) {
    Write-Host "Dataset valido"
    Write-Host "Versao: $benchmarkVersion"
    Write-Host "Casos: $($allCases.Count)"
    Write-Host "Caminho: $datasetPath"
    exit 0
}

# -- validate backend availability ---------------------------------------------

Write-Host ""
Write-Host "Benchmark TaxIA"
Write-Host "==============="
Write-Host ""
Write-Host "Dataset: $datasetPath ($benchmarkVersion)"
Write-Host "A verificar disponibilidade do backend em $BaseUrl ..."

try {
    $health = Invoke-RestMethod -Uri "$BaseUrl/actuator/health" -Method GET -TimeoutSec 5
    if ($health.status -ne "UP") {
        Write-Host "ERRO: Backend respondeu mas status nao e UP: $($health.status)"
        exit 1
    }
    Write-Host "Backend disponivel - status: $($health.status)"
} catch {
    Write-Host "ERRO: Backend nao acessivel em $BaseUrl"
    Write-Host "Verifica se a aplicacao esta a correr com o provider '$Provider'."
    exit 1
}

# -- filter cases --------------------------------------------------------------

if ($CaseId -ne "") {
    $selectedCases = @($allCases | Where-Object { $_.id -eq $CaseId })
    if ($selectedCases.Count -eq 0) {
        Write-Host "ERRO: Caso '$CaseId' nao encontrado no dataset '$Dataset'."
        Write-Host "IDs disponíveis: $(($allCases | Select-Object -ExpandProperty id) -join ', ')"
        exit 1
    }
} else {
    $selectedCases = $allCases
}

$totalCases   = $selectedCases.Count
$maxCallCount = $totalCases

# -- pre-flight summary --------------------------------------------------------

Write-Host ""
Write-Host "Provider esperado       : $Provider"
Write-Host "Modelo                  : (disponivel apos primeira chamada)"
Write-Host "Casos a executar        : $totalCases"
Write-Host "Numero maximo de chamadas: $maxCallCount"
Write-Host "Delay entre chamadas    : ${DelaySeconds}s"
Write-Host "Pasta de resultados     : $OutputDirectory"
Write-Host "Dataset                 : $datasetPath ($benchmarkVersion)"
Write-Host ""
Write-Host "Cada caso efectua exactamente UMA chamada real ao provider."
Write-Host "Nenhuma chamada e repetida automaticamente."
Write-Host ""

$confirmation = Read-Host "Escreve EXECUTAR para iniciar"
if ($confirmation -ne "EXECUTAR") {
    Write-Host "Execucao cancelada."
    exit 0
}

# -- authentication -------------------------------------------------------------

$email           = $null
$passwordSecure  = $null
$passwordPlain   = $null
$passwordPointer = [IntPtr]::Zero
$token           = $null

try {
    Write-Host ""
    $email          = Read-Host "Email do administrador"
    $passwordSecure = Read-Host "Password" -AsSecureString

    $passwordPointer = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($passwordSecure)
    $passwordPlain   = [System.Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)

    $loginBodyJson  = @{ email = $email; password = $passwordPlain } | ConvertTo-Json -Compress
    $loginBodyBytes = [System.Text.Encoding]::UTF8.GetBytes($loginBodyJson)

    Write-Host "A autenticar..."

    try {
        $loginResp = Invoke-RestMethod `
            -Uri         "$BaseUrl/api/v1/auth/login" `
            -Method      POST `
            -ContentType "application/json; charset=utf-8" `
            -Body        $loginBodyBytes
    } catch {
        $loginCode = 0
        $loginResp2 = $null
        try { $loginResp2 = $_.Exception.Response } catch {}
        if ($null -ne $loginResp2) { try { $loginCode = [int]$loginResp2.StatusCode } catch {} }
        if ($loginCode -eq 401) { throw "Login recusado (401 Unauthorized). Verifica email e password." }
        if ($loginCode -gt 0)   { throw "Erro no login - HTTP $loginCode." }
        throw "Erro no login: $($_.Exception.Message)"
    }

    if ([string]::IsNullOrWhiteSpace($loginResp.accessToken)) {
        throw "Login nao devolveu accessToken."
    }

    $hasAdmin = ($loginResp.roles -contains "ADMIN") -or ($loginResp.roles -contains "ROLE_ADMIN")
    if (-not $hasAdmin) {
        throw "Utilizador sem perfil administrativo. Roles: $($loginResp.roles -join ', ')"
    }

    $token = $loginResp.accessToken
    Write-Host "Autenticacao concluida. Perfil ADMIN confirmado."

} finally {
    if ($passwordPointer -ne [IntPtr]::Zero) {
        [System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
        $passwordPointer = [IntPtr]::Zero
    }
    if ($null -ne $passwordSecure) { $passwordSecure.Dispose() }
    $passwordPlain  = $null
    $passwordSecure = $null
}

# -- prepare output ------------------------------------------------------------

$null = New-Item -ItemType Directory -Force $resolvedOutputDir

$fileStamp   = Format-FileTimestamp
$baseName    = "${benchmarkVersion}_${Provider}_${fileStamp}"
$jsonOutPath = Join-Path $resolvedOutputDir "$baseName.json"
$csvOutPath  = Join-Path $resolvedOutputDir "$baseName.csv"

# CSV header - aligned with evaluation-template.csv (35 columns)
$csvHeader = "benchmark_version,case_id,title,provider,model," +
             "input_tokens,output_tokens,duration_ms,error,answer," +
             "support_status,requires_human_validation,unsupported_claims_count,sources_count,provider_called,response_rejected," +
             "execution_outcome,requires_human_validation_final," +
             "correctness_1_5,context_adherence_1_5,missing_info_identified_1_5," +
             "exception_identified_1_5,no_invention_1_5,clarity_1_5,structure_1_5," +
             "decision_utility_1_5,prudence_1_5,human_validation_signalled_1_5," +
             "expected_behaviours_met,forbidden_behaviours_detected,critical_failure,critical_failure_reason," +
             "overall_notes,evaluator,evaluated_at"

# -- execute cases -------------------------------------------------------------

$startedAt       = Format-IsoTimestamp
$results         = [System.Collections.Generic.List[object]]::new()
$csvLines        = [System.Collections.Generic.List[string]]::new()
$csvLines.Add($csvHeader)

$successCount    = 0
$failCount       = 0
$caseIndex       = 0
$totalInputTokens  = 0
$totalOutputTokens = 0
$totalDurationMs   = 0
$modelsUsed      = [System.Collections.Generic.HashSet[string]]::new()
$executorError   = $null

Write-Host ""

try {

foreach ($case in $selectedCases) {
    $caseIndex++
    $caseId    = $case.id
    $caseTitle = $case.title

    # Build system prompt: instruction + clearly delimited context when present
    $sysPrompt = $case.systemInstruction
    if (-not [string]::IsNullOrWhiteSpace($case.context)) {
        $sysPrompt = $sysPrompt + "`n`n---`nContexto fornecido para este caso:`n" +
                     $case.context + "`n---"
    }

    $askBodyJson  = @{
        question     = $case.question
        systemPrompt = $sysPrompt
    } | ConvertTo-Json -Depth 5 -Compress
    $askBodyBytes = [System.Text.Encoding]::UTF8.GetBytes($askBodyJson)

    $caseStartedAt               = Format-IsoTimestamp
    $caseFinishedAt              = $null
    $answer                      = $null
    $actualProvider              = $null
    $model                       = $null
    $inputTokens                 = 0
    $outputTokens                = 0
    $durationMs                  = 0
    $errorMsg                    = $null
    $providerMismatch            = $false
    $supportStatus               = ""
    $groundingHumanRequired      = $false
    $unsupportedClaimsCount      = 0
    $sourcesCount                = 0
    $providerCalled              = $false
    $responseRejected            = $false
    $executionOutcome            = ""
    $requiresHumanValidationFinal = $false
    $structuralError             = $false

    try {
        $callStart = Get-Date

        $aiResp = Invoke-JsonPostUtf8 `
            -Uri           "$BaseUrl/api/v1/admin/ai/ask" `
            -BodyBytes      $askBodyBytes `
            -Authorization "Bearer $token"

        $durationMs     = [long]((Get-Date) - $callStart).TotalMilliseconds
        $caseFinishedAt = Format-IsoTimestamp

        $answer         = $aiResp.answer
        $actualProvider = $aiResp.provider
        $model          = $aiResp.model
        $inputTokens    = [int]$aiResp.inputTokens
        $outputTokens   = [int]$aiResp.outputTokens

        # Grounding fields (backward-compatible: absent in old responses defaults to initialised values)
        $ssp = $aiResp.PSObject.Properties["supportStatus"]
        if ($null -ne $ssp) { $supportStatus = [string]$ssp.Value }

        $rhvp = $aiResp.PSObject.Properties["requiresHumanValidation"]
        if ($null -ne $rhvp) { $groundingHumanRequired = [bool]$rhvp.Value }

        $uccp = $aiResp.PSObject.Properties["unsupportedClaimsCount"]
        if ($null -ne $uccp) { $unsupportedClaimsCount = [int]$uccp.Value }

        $srcsp = $aiResp.PSObject.Properties["sources"]
        if ($null -ne $srcsp -and $null -ne $srcsp.Value) { $sourcesCount = @($srcsp.Value).Count }

        $pcp = $aiResp.PSObject.Properties["providerCalled"]
        if ($null -ne $pcp) { $providerCalled = [bool]$pcp.Value }

        $rrp = $aiResp.PSObject.Properties["responseRejected"]
        if ($null -ne $rrp) { $responseRejected = [bool]$rrp.Value }

        $totalInputTokens  += $inputTokens
        $totalOutputTokens += $outputTokens
        $totalDurationMs   += $durationMs
        if ($model) { $null = $modelsUsed.Add($model) }

        # Provider mismatch: only flag when the provider was actually called.
        # actualProvider="none" when providerCalled=false is not a mismatch.
        if ($providerCalled -eq $true) {
            if ($actualProvider -ne $Provider) {
                $providerMismatch = $true
                Write-Host ""
                Write-Host "  AVISO: provider divergente no caso $caseId"
                Write-Host "  Esperado: $Provider | Devolvido: $actualProvider"
                Write-Host "  O caso sera marcado como erro de provider."
                $errorMsg = "PROVIDER_MISMATCH: expected=$Provider actual=$actualProvider"
                $failCount++
            } else {
                $successCount++
            }
        } elseif ($supportStatus -eq "INSUFFICIENT_CONTEXT" -or $supportStatus -eq "REQUIRES_HUMAN_REVIEW") {
            # Safe no-call: grounding policy deliberately skipped the provider. Counts as success.
            $successCount++
        } else {
            # providerCalled=false but status does not justify the absence of a call.
            $structuralError = $true
            $errorMsg = "STRUCTURAL_ERROR: providerCalled=false but supportStatus=$supportStatus"
            $failCount++
        }

        Write-Progress-Line $caseIndex $totalCases $caseId "concluido - $durationMs ms"

    } catch {
        $caseFinishedAt = Format-IsoTimestamp
        # Safe HTTP status extraction - .Response may not exist on all exception types
        $code    = 0
        $errBody = ""
        $errResp = $null
        try { $errResp = $_.Exception.Response } catch {}
        if ($null -ne $errResp) {
            try { $code = [int]$errResp.StatusCode } catch {}
            try {
                $stream  = $errResp.GetResponseStream()
                $reader  = [System.IO.StreamReader]::new($stream)
                $rawBody = $reader.ReadToEnd()
                $reader.Dispose()
                $stream.Dispose()
                if ($rawBody.Length -gt 300) { $rawBody = $rawBody.Substring(0, 300) + "..." }
                $errBody = $rawBody -replace '"(password|token|jwt|apiKey|secret)":\s*"[^"]*"', '"$1":"[REDACTED]"'
            } catch {}
        }
        if ($code -gt 0) {
            $errorMsg = "HTTP $code"
            if ($errBody -ne "") { $errorMsg = "HTTP $code | $errBody" }
        } else {
            $errorMsg = $_.Exception.Message
        }
        $failCount++
        Write-Progress-Line $caseIndex $totalCases $caseId "erro $errorMsg - sem retry"
    }

    # Explicit assignments - if() inside function args is invalid in PS 5.1
    if ($null -ne $actualProvider) { $entryActualProvider = $actualProvider } else { $entryActualProvider = "" }
    if ($null -ne $model)          { $entryModel          = $model          } else { $entryModel          = "" }
    if ($null -ne $answer)         { $entryAnswer         = $answer         } else { $entryAnswer         = "" }
    if ($null -ne $caseFinishedAt) { $entryFinishedAt     = $caseFinishedAt } else { $entryFinishedAt     = "" }
    if ($null -ne $errorMsg)       { $entryError          = $errorMsg       } else { $entryError          = "" }

    # Compute executionOutcome from observed response fields.
    # Priority: technical/structural errors first, then grounding-policy outcomes.
    if ($entryError -ne "" -and -not $providerMismatch -and -not $structuralError) {
        $executionOutcome = "TECHNICAL_ERROR"
    } elseif ($structuralError) {
        $executionOutcome = "STRUCTURAL_ERROR"
    } elseif ($providerMismatch) {
        $executionOutcome = "PROVIDER_MISMATCH"
    } elseif ($providerCalled -eq $false) {
        $executionOutcome = "SAFE_NO_PROVIDER_CALL"
    } elseif ($responseRejected -eq $true) {
        $executionOutcome = "REJECTED_PROVIDER_RESPONSE"
    } else {
        $executionOutcome = "PROVIDER_RESPONSE"
    }

    # requiresHumanValidationFinal: OR of case metadata flag and grounding assessment.
    # A later layer must never reduce true→false.
    $caseHumanRequired = [bool]$case.requiresHumanValidation
    $requiresHumanValidationFinal = $caseHumanRequired -or $groundingHumanRequired

    $resultEntry = [ordered]@{
        caseId                        = $caseId
        title                         = $caseTitle
        category                      = $case.category
        difficulty                    = $case.difficulty
        requiresHumanValidation       = $caseHumanRequired
        expectedProvider              = $Provider
        actualProvider                = $entryActualProvider
        model                         = $entryModel
        answer                        = $entryAnswer
        inputTokens                   = $inputTokens
        outputTokens                  = $outputTokens
        durationMillis                = $durationMs
        startedAt                     = $caseStartedAt
        finishedAt                    = $entryFinishedAt
        error                         = $entryError
        supportStatus                 = $supportStatus
        requiresHumanValidation_grounding = $groundingHumanRequired
        unsupportedClaimsCount        = $unsupportedClaimsCount
        sourcesCount                  = $sourcesCount
        providerCalled                = $providerCalled
        responseRejected              = $responseRejected
        executionOutcome              = $executionOutcome
        requiresHumanValidationFinal  = $requiresHumanValidationFinal
    }
    $results.Add($resultEntry)

    # CSV row - pre-assign all values, no if() inside function arguments
    $csvLine = (@(
        Escape-CsvField $benchmarkVersion,
        Escape-CsvField $caseId,
        Escape-CsvField $caseTitle,
        Escape-CsvField $entryActualProvider,
        Escape-CsvField $entryModel,
        $inputTokens,
        $outputTokens,
        $durationMs,
        Escape-CsvField $entryError,
        Escape-CsvField $entryAnswer,
        Escape-CsvField $supportStatus,
        $groundingHumanRequired,
        $unsupportedClaimsCount,
        $sourcesCount,
        $providerCalled,
        $responseRejected,
        Escape-CsvField $executionOutcome,
        $requiresHumanValidationFinal,
        "", "", "", "", "", "", "", "", "", "",     # 10 scoring columns (1-5) - blank for human
        "", "", "", "",                             # expected_behaviours_met, forbidden_behaviours_detected, critical_failure, critical_failure_reason
        "",                                         # overall_notes
        "",                                         # evaluator
        ""                                          # evaluated_at
    ) -join ",")
    $csvLines.Add($csvLine)

    # Delay between cases (skip after last)
    if ($caseIndex -lt $totalCases -and $DelaySeconds -gt 0) {
        Start-Sleep -Seconds $DelaySeconds
    }
}

} catch {
    $executorError = $_.Exception.Message
    Write-Host ""
    Write-Host "ERRO FATAL DO EXECUTOR: $executorError"
    Write-Host "Casos tentados: $caseIndex / $totalCases"
}

$finishedAt = Format-IsoTimestamp

# -- clean token ---------------------------------------------------------------

$token = $null
[System.GC]::Collect()

# -- save partial results on fatal executor error -----------------------------

if ($null -ne $executorError) {
    $partialName    = "${benchmarkVersion}_${Provider}_${fileStamp}_partial"
    $partialPath    = Join-Path $resolvedOutputDir "$partialName.json"
    $partialOutput  = [ordered]@{
        completed        = $false
        executionId      = "${benchmarkVersion}_${Provider}_${fileStamp}"
        benchmarkVersion = $benchmarkVersion
        expectedProvider = $Provider
        fatalError       = $executorError
        startedAt        = $startedAt
        finishedAt       = $finishedAt
        baseUrl          = $BaseUrl
        totalCases       = $totalCases
        casesTried       = $caseIndex
        successfulCases  = $successCount
        failedCases      = $failCount
        results          = $results.ToArray()
    }
    try {
        $partialJson = $partialOutput | ConvertTo-Json -Depth 6
        # UTF-8 without BOM (Set-Content -Encoding UTF8 writes BOM in PS 5.1)
        [System.IO.File]::WriteAllText($partialPath, $partialJson, [System.Text.UTF8Encoding]::new($false))
        Write-Host "Resultados parciais guardados: $partialPath"
    } catch {
        Write-Host "Nao foi possivel guardar resultados parciais: $($_.Exception.Message)"
    }
    exit 1
}

# -- write JSON ----------------------------------------------------------------
# JSON: UTF-8 without BOM (per RFC 8259; Set-Content -Encoding UTF8 adds BOM in PS 5.1)

if ($successCount -gt 0) { $avgDurationMs = [long]($totalDurationMs / $successCount) } else { $avgDurationMs = 0 }
$modelsUsedArray = @($modelsUsed)

$jsonOutput = [ordered]@{
    completed             = $true
    executionId           = "${benchmarkVersion}_${Provider}_${fileStamp}"
    benchmarkVersion      = $benchmarkVersion
    expectedProvider      = $Provider
    modelsUsed            = $modelsUsedArray
    startedAt             = $startedAt
    finishedAt            = $finishedAt
    baseUrl               = $BaseUrl
    totalCases            = $totalCases
    successfulCases       = $successCount
    failedCases           = $failCount
    totalInputTokens      = $totalInputTokens
    totalOutputTokens     = $totalOutputTokens
    totalDurationMillis   = $totalDurationMs
    averageDurationMillis = $avgDurationMs
    results               = $results.ToArray()
}

$jsonString = $jsonOutput | ConvertTo-Json -Depth 6
[System.IO.File]::WriteAllText($jsonOutPath, $jsonString, [System.Text.UTF8Encoding]::new($false))

# -- write CSV -----------------------------------------------------------------
# CSV: UTF-8 with BOM so Excel on Windows opens it correctly without encoding prompts

[System.IO.File]::WriteAllLines($csvOutPath, $csvLines, [System.Text.UTF8Encoding]::new($true))

# -- summary -------------------------------------------------------------------

if ($modelsUsed.Count -gt 0) { $modelsStr = $modelsUsed -join ", " } else { $modelsStr = "(nenhum)" }
if ($successCount -gt 0 -and $modelsUsed.Count -gt 0) { $effectiveProvider = $Provider } else { $effectiveProvider = "(ver erros)" }

Write-Host ""
Write-Host "Execucao concluida"
Write-Host "=================="
Write-Host "Provider esperado       : $Provider"
Write-Host "Provider efectivo       : $effectiveProvider"
Write-Host "Modelo(s) utilizado(s)  : $modelsStr"
Write-Host "Casos concluidos        : $successCount / $totalCases"
Write-Host "Casos com erro          : $failCount"
Write-Host "Total input tokens      : $totalInputTokens"
Write-Host "Total output tokens     : $totalOutputTokens"
Write-Host "Duracao total           : ${totalDurationMs} ms"
Write-Host "Resultado JSON          : $jsonOutPath"
Write-Host "Grelha CSV              : $csvOutPath"
