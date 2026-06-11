<#
.SYNOPSIS
  Smoke prod-like Etapa 0 — auth + context + refresh + reuse + logout (sin cuentas qa.etapa0.* ni semilla QA).

.NOTES
  Cada ejecución escribe evidencia **inmutable** bajo:
    qa-evidence/2026-04-15/etapa-0/prod/runs/<run-id>/
  con run-id = `yyyy-MM-ddTHH-mm-ss` (hora local del equipo) salvo override `SMOKE_PROD_RUN_ID`.
  Archivos por corrida: PROD_SMOKE_HTTP.log, PROD_SMOKE_RESULT.txt, RUN_METADATA.txt
  **No** se sobrescribe la raíz `.../prod/` con estos tres archivos.

  Requisitos del operador (exportar en el shell; NO commitear valores):
    SMOKE_PROD_EMAIL, SMOKE_PROD_PASSWORD
    SMOKE_PROD_MFA_CODE — si el login devuelve MFA challenge
    SMOKE_PROD_CONTEXT_ID — ownerId si requiresOrgSelection=true
  Opcional archivo (sin qa.etapa0.*):
    SMOKE_PROD_OWNER_TOKEN_BEARER — o email/password/MFA del owner
    SMOKE_PROD_TENANT_EMAIL, SMOKE_PROD_TENANT_PASSWORD, SMOKE_PROD_TENANT_PROFILE_ID

  SMOKE_PROD_BASE_URL — default http://localhost:8080/api
  SMOKE_PROD_ROOT — raíz prod-like (default .../qa-evidence/2026-04-15/etapa-0/prod)
  SMOKE_PROD_RUN_ID — opcional; fija el nombre de carpeta bajo runs/ (solo caracteres seguros para ruta)
  SMOKE_PROD_OUT — opcional; si se define, es la **ruta completa** del directorio de corrida (omite runs/run-id automático)

  Perfil del backend: prod + ENCRYPTION_KEY por env + ADMINDI_QA_SEED_ENABLED=false.
#>
param(
  [string]$ProdRoot = $(if ($env:SMOKE_PROD_ROOT) { $env:SMOKE_PROD_ROOT } else { Join-Path (Split-Path $PSScriptRoot -Parent) "qa-evidence\2026-04-15\etapa-0\prod" })
)

$ErrorActionPreference = "Stop"
$startUtc = [DateTime]::UtcNow.ToString("o")

function Test-NotQaSeedEmail([string]$label, [string]$em) {
  if ([string]::IsNullOrWhiteSpace($em)) { return }
  if ($em -match 'qa\.etapa0\.') {
    throw "[etapa0-prod-smoke] $label no puede usar cuenta qa.etapa0.* ($em). Use credenciales prod-like reales."
  }
}

$base = $env:SMOKE_PROD_BASE_URL
if ([string]::IsNullOrWhiteSpace($base)) { $base = "http://localhost:8080/api" }
$base = $base.TrimEnd('/')

$log = New-Object System.Collections.ArrayList
function Log([string]$s) { [void]$log.Add("$(Get-Date -Format o)  $s") }

function Redact-Token([string]$t) {
  if ([string]::IsNullOrEmpty($t)) { return "(empty)" }
  if ($t.Length -le 24) { return $t.Substring(0, [Math]::Min(12, $t.Length)) + "...(len=$($t.Length))" }
  return $t.Substring(0, 20) + "...(len=$($t.Length))"
}

function Invoke-Api {
  param(
    [string]$Method,
    [string]$PathRel,
    [string]$JsonBody = $null,
    [hashtable]$ExtraHeaders = @{}
  )
  $rel = $PathRel.TrimStart('/')
  $uri = "$base/$rel"
  $headers = @{ "Accept" = "application/json" }
  foreach ($k in $ExtraHeaders.Keys) { $headers[$k] = $ExtraHeaders[$k] }
  try {
    if ($Method -eq "GET") {
      $r = Invoke-WebRequest -Uri $uri -Method GET -Headers $headers -UseBasicParsing
    }
    elseif ($Method -eq "DELETE") {
      $r = Invoke-WebRequest -Uri $uri -Method DELETE -Headers $headers -UseBasicParsing
    }
    else {
      if (-not $headers.ContainsKey("Content-Type")) { $headers["Content-Type"] = "application/json" }
      $r = Invoke-WebRequest -Uri $uri -Method $Method -Headers $headers -Body $JsonBody -UseBasicParsing
    }
    $code = [int]$r.StatusCode
    $txt = $r.Content
    if ($txt -is [byte[]]) { $txt = [System.Text.Encoding]::UTF8.GetString($txt) }
    elseif ($null -eq $txt) { $txt = "" }
    $parsed = $null
    try { $parsed = $txt | ConvertFrom-Json } catch { }
    return @{ StatusCode = $code; Raw = $txt; Parsed = $parsed }
  }
  catch {
    $resp = $_.Exception.Response
    if ($null -ne $resp) {
      $code = [int]$resp.StatusCode
      $sr = New-Object System.IO.StreamReader($resp.GetResponseStream())
      $txt = $sr.ReadToEnd()
      $sr.Close()
      $parsed = $null
      try { $parsed = $txt | ConvertFrom-Json } catch { }
      return @{ StatusCode = $code; Raw = $txt; Parsed = $parsed }
    }
    return @{ StatusCode = -1; Raw = $_.Exception.Message; Parsed = $null }
  }
}

# --- Directorio de corrida (inmutable por run-id) ---
$OutDir = $null
if (-not [string]::IsNullOrWhiteSpace($env:SMOKE_PROD_OUT)) {
  $OutDir = $env:SMOKE_PROD_OUT.Trim()
}
else {
  $runId = if (-not [string]::IsNullOrWhiteSpace($env:SMOKE_PROD_RUN_ID)) {
    $env:SMOKE_PROD_RUN_ID.Trim()
  } else {
    Get-Date -Format "yyyy-MM-ddTHH-mm-ss"
  }
  $runsRoot = Join-Path $ProdRoot "runs"
  $OutDir = Join-Path $runsRoot $runId
}

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$runIdForMeta = Split-Path $OutDir -Leaf
$relativeFromRepo = $OutDir.Replace((Split-Path $PSScriptRoot -Parent), ".").Replace("\", "/")

function Write-RunMetadata([int]$ExitCode, [string]$ResultLine, [string]$Notes) {
  $lines = @(
    "run_id=$runIdForMeta"
    "started_utc=$startUtc"
    "finished_utc=$([DateTime]::UtcNow.ToString('o'))"
    "SMOKE_PROD_BASE_URL=$base"
    "evidence_dir=$OutDir"
    "evidence_dir_repo_relative=$relativeFromRepo"
    "computer=$($env:COMPUTERNAME)"
    "user=$($env:USERNAME)"
    "exit_code=$ExitCode"
    "smoke_result_line=$ResultLine"
  )
  if (-not [string]::IsNullOrWhiteSpace($Notes)) { $lines += "notes=$Notes" }
  $lines | Set-Content -Encoding utf8 (Join-Path $OutDir "RUN_METADATA.txt")
}

function Finalize-Evidence([int]$ExitCode, [string]$ResultToken, [string]$Notes) {
  $resultLine = if ($ResultToken -match '^PROD_SMOKE_RESULT=') { $ResultToken } else { "PROD_SMOKE_RESULT=$ResultToken" }
  $resultLine | Set-Content -Encoding utf8 (Join-Path $OutDir "PROD_SMOKE_RESULT.txt")
  $log | Set-Content -Encoding utf8 (Join-Path $OutDir "PROD_SMOKE_HTTP.log")
  Write-RunMetadata -ExitCode $ExitCode -ResultLine $resultLine -Notes $Notes
}

Log "SMOKE_PROD_BASE_URL=$base"
Log "RUN_DIR=$OutDir"
Log "run_id=$runIdForMeta"
Log "PREFLIGHT: smoke prod-like; no semilla QA; emails qa.etapa0.* rechazados."

$email = $env:SMOKE_PROD_EMAIL
$pass = $env:SMOKE_PROD_PASSWORD
try {
  Test-NotQaSeedEmail "SMOKE_PROD_EMAIL" $email
} catch {
  Log $_.Exception.Message
  Finalize-Evidence 12 "BLOCKED_QA_EMAIL_PATTERN" "qa.etapa0.* no permitido"
  exit 12
}

if ([string]::IsNullOrWhiteSpace($email) -or [string]::IsNullOrWhiteSpace($pass)) {
  Log "BLOQUEO: defina SMOKE_PROD_EMAIL y SMOKE_PROD_PASSWORD (credenciales prod-like reales)."
  Finalize-Evidence 2 "BLOCKED_NO_PROD_CREDS" "framework listo; falta credenciales operador"
  exit 2
}

Log "--- POST /auth/login ---"
$loginRes = Invoke-Api -Method POST -PathRel "auth/login" -JsonBody (@{ email = $email; password = $pass } | ConvertTo-Json -Compress)
Log "login status=$($loginRes.StatusCode)"
if ($loginRes.StatusCode -eq 429) {
  Log "login 429 rate limit"
  Finalize-Evidence 8 "BLOCKED_RATE_LIMIT" ""
  exit 8
}
if ($loginRes.StatusCode -lt 200 -or $loginRes.StatusCode -ge 300) {
  Log "login body trunc: $($loginRes.Raw.Substring(0, [Math]::Min(400, [Math]::Max(0, $loginRes.Raw.Length))))"
  Finalize-Evidence 3 "BLOCKED_LOGIN" ""
  exit 3
}

$j = $loginRes.Parsed
$access = [string]$j.token
$mfaChallenge = $null
if ($j.PSObject.Properties.Name -contains 'mfaChallengeToken') { $mfaChallenge = [string]$j.mfaChallengeToken }
$refresh = [string]$j.refreshToken
Log "token redacted=$(Redact-Token $access); mfaChallenge=$(-not [string]::IsNullOrWhiteSpace($mfaChallenge))"

if (-not [string]::IsNullOrWhiteSpace($mfaChallenge)) {
  $otp = $env:SMOKE_PROD_MFA_CODE
  if ([string]::IsNullOrWhiteSpace($otp)) {
    Log "BLOCKED: MFA challenge sin SMOKE_PROD_MFA_CODE"
    Finalize-Evidence 4 "BLOCKED_MFA_NEEDS_CODE" ""
    exit 4
  }
  Log "--- POST /auth/mfa/verify ---"
  $mfaRes = Invoke-Api -Method POST -PathRel "auth/mfa/verify" -JsonBody (@{ email = $email; code = $otp; challengeToken = $mfaChallenge } | ConvertTo-Json -Compress)
  Log "mfa/verify status=$($mfaRes.StatusCode)"
  if ($mfaRes.StatusCode -lt 200 -or $mfaRes.StatusCode -ge 300) {
    Finalize-Evidence 5 "BLOCKED_MFA_VERIFY" ""
    exit 5
  }
  $j = $mfaRes.Parsed
  $access = [string]$j.token
  $refresh = [string]$j.refreshToken
}

$needsOrg = $false
if ($null -ne $j -and ($j.PSObject.Properties.Name -contains 'requiresOrgSelection')) {
  $needsOrg = [bool]$j.requiresOrgSelection
}

$hdrAuth = @{ Authorization = "Bearer $access" }

Log "--- GET /auth/contexts ---"
$ctxRes = Invoke-Api -Method GET -PathRel "auth/contexts" -ExtraHeaders $hdrAuth
Log "contexts status=$($ctxRes.StatusCode)"
if ($ctxRes.StatusCode -lt 200 -or $ctxRes.StatusCode -ge 300) {
  Finalize-Evidence 6 "BLOCKED_CONTEXTS" ""
  exit 6
}

$ctxId = $env:SMOKE_PROD_CONTEXT_ID
if ($needsOrg -and -not [string]::IsNullOrWhiteSpace($ctxId)) {
  Log "--- POST /auth/select-context ---"
  $sel = Invoke-Api -Method POST -PathRel "auth/select-context" -JsonBody (@{ contextId = $ctxId } | ConvertTo-Json -Compress) -ExtraHeaders $hdrAuth
  Log "select-context status=$($sel.StatusCode)"
  if ($sel.StatusCode -ge 200 -and $sel.StatusCode -lt 300 -and $null -ne $sel.Parsed) {
    $j = $sel.Parsed
    $access = [string]$j.token
    $refresh = [string]$j.refreshToken
    $hdrAuth = @{ Authorization = "Bearer $access" }
  }
}
elseif ($needsOrg) {
  Log "SKIP select-context: falta SMOKE_PROD_CONTEXT_ID"
}

if (-not [string]::IsNullOrWhiteSpace($ctxId)) {
  Log "--- POST /auth/switch-context ---"
  $sw = Invoke-Api -Method POST -PathRel "auth/switch-context" -JsonBody (@{ contextId = $ctxId } | ConvertTo-Json -Compress) -ExtraHeaders $hdrAuth
  Log "switch-context status=$($sw.StatusCode)"
  if ($sw.StatusCode -ge 200 -and $sw.StatusCode -lt 300 -and $null -ne $sw.Parsed) {
    $j = $sw.Parsed
    $access = [string]$j.token
    $refresh = [string]$j.refreshToken
    $hdrAuth = @{ Authorization = "Bearer $access" }
  }
}

Log "--- POST /auth/refresh ---"
$oldRefresh = $refresh
$ctxForRefresh = $env:SMOKE_PROD_CONTEXT_ID
$refBodyObj = @{ refreshToken = $refresh }
if (-not [string]::IsNullOrWhiteSpace($ctxForRefresh)) { $refBodyObj["contextId"] = $ctxForRefresh }
$refRes = Invoke-Api -Method POST -PathRel "auth/refresh" -JsonBody ($refBodyObj | ConvertTo-Json -Compress)
Log "refresh status=$($refRes.StatusCode)"
if ($refRes.StatusCode -lt 200 -or $refRes.StatusCode -ge 300) {
  Finalize-Evidence 7 "BLOCKED_REFRESH" ""
  exit 7
}
$j = $refRes.Parsed
$access2 = [string]$j.token
$refresh2 = [string]$j.refreshToken

Log "--- POST /auth/refresh (reuse, esperado 400) ---"
$reuse = Invoke-Api -Method POST -PathRel "auth/refresh" -JsonBody (@{ refreshToken = $oldRefresh; contextId = $ctxForRefresh } | ConvertTo-Json -Compress)
Log "reuse status=$($reuse.StatusCode)"

Log "--- POST /auth/logout ---"
$hdrPostRefresh = @{ Authorization = "Bearer $access2" }
$lo = Invoke-Api -Method POST -PathRel "auth/logout" -JsonBody "{}" -ExtraHeaders $hdrPostRefresh
Log "logout status=$($lo.StatusCode)"

Log "--- GET /auth/contexts post-logout (esperado 401/403) ---"
$probeTok = Invoke-Api -Method GET -PathRel "auth/contexts" -ExtraHeaders $hdrPostRefresh
Log "post-logout contexts status=$($probeTok.StatusCode)"

$reuseBad = ($reuse.StatusCode -eq 400) -and ($reuse.Raw -match "reuse|revoked|Invalid|expired|Token")
if (-not $reuseBad) { $reuseBad = ($reuse.StatusCode -ge 400) }
$logoutOk = ($lo.StatusCode -eq 204)
$postLogoutDenied = ($probeTok.StatusCode -eq 403) -or ($probeTok.StatusCode -eq 401)
$issues = @()
if (-not $reuseBad) { $issues += "REUSE" }
if (-not $logoutOk) { $issues += "LOGOUT" }
if ($logoutOk -and -not $postLogoutDenied) { $issues += "POST_LOGOUT" }

$verdict = if ($issues.Count -eq 0) { "PASSED_AUTH_CORE" } else { "PARTIAL_$($issues -join '_')" }
Finalize-Evidence 0 $verdict "auth core chain completa"
exit 0
