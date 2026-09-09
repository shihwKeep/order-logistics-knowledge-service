param(
    [string]$BaseUrl = 'http://127.0.0.1:8084',
    [long]$TenantId = 1,
    [long]$UserId = 10567,
    [ValidateRange(1, 60)][int]$TimeoutSec = 12,
    [string]$Dataset = (Join-Path $PSScriptRoot '..\evaluation\chinese-retrieval-golden.example.jsonl')
)

$ErrorActionPreference = 'Stop'
$secret = $env:KNOWLEDGE_INTERNAL_API_SECRET
if ([string]::IsNullOrWhiteSpace($secret) -or $secret.Length -lt 32) {
    throw '请通过进程环境变量 KNOWLEDGE_INTERNAL_API_SECRET 提供至少32字符的内部密钥。'
}
if (-not (Test-Path -LiteralPath $Dataset)) {
    throw "评测集不存在: $Dataset"
}

function Get-Sha256([string]$Value) {
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        return [Convert]::ToHexString($sha.ComputeHash(
            [Text.Encoding]::UTF8.GetBytes($Value))).ToLowerInvariant()
    } finally {
        $sha.Dispose()
    }
}

function Get-Hmac([string]$Value, [string]$Key) {
    $hmac = [System.Security.Cryptography.HMACSHA256]::new(
        [Text.Encoding]::UTF8.GetBytes($Key))
    try {
        return [Convert]::ToHexString($hmac.ComputeHash(
            [Text.Encoding]::UTF8.GetBytes($Value))).ToLowerInvariant()
    } finally {
        $hmac.Dispose()
    }
}

$cases = Get-Content -LiteralPath $Dataset -Encoding UTF8 |
    Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
    ForEach-Object { $_ | ConvertFrom-Json }
$total = @($cases).Count
$answerableCases = 0
$recallHits = 0
$firstHits = 0
$refusalCases = 0
$correctRefusals = 0
$unsupportedAnswers = 0
$serviceErrors = 0

foreach ($case in $cases) {
    $expectsAnswer = [bool]($case.expectAnswerable)
    if ($expectsAnswer) { $answerableCases++ } else { $refusalCases++ }
    $timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $nonce = [guid]::NewGuid().ToString()
    $question = [string]$case.question
    $canonical = "POST`n/api/v1/internal/knowledge/retrieve`n$TenantId`n$UserId`n$timestamp`n$nonce`n$(Get-Sha256 $question.Trim())`n"
    $headers = @{
        'X-Knowledge-Tenant-Id' = "$TenantId"
        'X-Knowledge-User-Id' = "$UserId"
        'X-Knowledge-Timestamp' = "$timestamp"
        'X-Knowledge-Nonce' = $nonce
        'X-Knowledge-Signature' = Get-Hmac $canonical $secret
        'X-Request-Id' = [guid]::NewGuid().ToString()
    }
    $body = @{ question = $question; knowledgeBaseIds = @() } | ConvertTo-Json -Compress
    try {
        $response = Invoke-RestMethod -Method Post `
            -Uri "$($BaseUrl.TrimEnd('/'))/api/v1/internal/knowledge/retrieve" `
            -Headers $headers -ContentType 'application/json' -Body $body -TimeoutSec $TimeoutSec
        $actualTitles = @($response.data.evidences | ForEach-Object { [string]$_.documentTitle })
        if ($expectsAnswer) {
            $expected = @($case.expectedDocumentTitles)
            if (@($actualTitles | Where-Object { $expected -contains $_ }).Count -gt 0) { $recallHits++ }
            if ($actualTitles.Count -gt 0 -and $expected -contains $actualTitles[0]) { $firstHits++ }
        } else {
            if (-not [bool]($response.data.answerable)) { $correctRefusals++ }
            else { $unsupportedAnswers++ }
        }
    } catch {
        $serviceErrors++
    }
}

[pscustomobject]@{
    datasetCases = $total
    answerableCases = $answerableCases
    recallAt5 = if ($answerableCases) { [math]::Round($recallHits / $answerableCases, 4) } else { 0 }
    firstHitRate = if ($answerableCases) { [math]::Round($firstHits / $answerableCases, 4) } else { 0 }
    refusalAccuracy = if ($refusalCases) { [math]::Round($correctRefusals / $refusalCases, 4) } else { 0 }
    unsupportedAnswerRate = if ($refusalCases) { [math]::Round($unsupportedAnswers / $refusalCases, 4) } else { 0 }
    serviceErrors = $serviceErrors
} | Format-List
