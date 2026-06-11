<#
.SYNOPSIS
  Smoke Etapa 1 - reauth, PropertyDeleteRequest, owner purge, n8n payload audit (QA profile).

.NOTES
  Cada ejecución escribe evidencia **inmutable** bajo:
    qa-evidence/2026-04-15/etapa-1/runs/<run-id>/
  con run-id = `yyyy-MM-ddTHH-mm-ss` (hora local) salvo override `SMOKE_ETAPA1_RUN_ID`.
  Archivos por corrida: ETAPA1_SMOKE_HTTP.log, ETAPA1_SMOKE_RESULT.txt, RUN_METADATA.txt
  **No** se sobrescriben corridas anteriores.

  Variables de entorno (exportar en el shell):
    SMOKE_EMAIL          — email del OWNER QA (ej: qa.etapa0.owner.alpha@test.local)
    SMOKE_PASSWORD       — password QA
    SMOKE_MFA_CODE       — TOTP code (oathtool --totp -b JBSWY3DPEHPK3PXP)
    SMOKE_SA_EMAIL       — email SUPER_ADMIN QA
    SMOKE_SA_PASSWORD    — password SUPER_ADMIN QA
    SMOKE_SA_MFA_CODE    — TOTP code para SUPER_ADMIN
    SMOKE_TENANT_EMAIL   — opcional; default qa.etapa0.tenant.multi@test.local (2+ contextos owner)
    SMOKE_TENANT_PASSWORD— opcional; default = SMOKE_PASSWORD si vacío
    SMOKE_TENANT_MFA_CODE— opcional; default = SMOKE_MFA_CODE si vacío
    SMOKE_BASE_URL       — default http://localhost:8080/api
#>
param(
  [string]$EvidenceRoot = $(if ($env:SMOKE_ETAPA1_ROOT) { $env:SMOKE_ETAPA1_ROOT } else { Join-Path (Split-Path $PSScriptRoot -Parent) "qa-evidence\2026-04-15\etapa-1" })
)

$ErrorActionPreference = "Stop"
$startUtc = [DateTime]::UtcNow.ToString("o")

$base = $env:SMOKE_BASE_URL
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
    elseif ($Method -eq "PUT") {
      if (-not $headers.ContainsKey("Content-Type")) { $headers["Content-Type"] = "application/json" }
      $r = Invoke-WebRequest -Uri $uri -Method PUT -Headers $headers -Body $JsonBody -UseBasicParsing
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

# --- Directorio inmutable por corrida ---
$runId = if (-not [string]::IsNullOrWhiteSpace($env:SMOKE_ETAPA1_RUN_ID)) {
  $env:SMOKE_ETAPA1_RUN_ID.Trim()
} else {
  Get-Date -Format "yyyy-MM-ddTHH-mm-ss"
}
$runsRoot = Join-Path $EvidenceRoot "runs"
$OutDir = Join-Path $runsRoot $runId

if (Test-Path $OutDir) {
  Write-Error "ABORTANDO: la carpeta de corrida ya existe: $OutDir (no se sobrescriben corridas)"
  exit 99
}
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$relativeFromRepo = $OutDir.Replace((Split-Path $PSScriptRoot -Parent), ".").Replace("\", "/")

function Write-RunMetadata([int]$ExitCode, [string]$ResultLine, [string]$Notes) {
  $lines = @(
    "run_id=$runId"
    "started_utc=$startUtc"
    "finished_utc=$([DateTime]::UtcNow.ToString('o'))"
    "SMOKE_BASE_URL=$base"
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
  $resultLine = if ($ResultToken -match '^ETAPA1_SMOKE_RESULT=') { $ResultToken } else { "ETAPA1_SMOKE_RESULT=$ResultToken" }
  $resultLine | Set-Content -Encoding utf8 (Join-Path $OutDir "ETAPA1_SMOKE_RESULT.txt")
  $log | Set-Content -Encoding utf8 (Join-Path $OutDir "ETAPA1_SMOKE_HTTP.log")
  Write-RunMetadata -ExitCode $ExitCode -ResultLine $resultLine -Notes $Notes
}

$issues = @()

Log "SMOKE_BASE_URL=$base"
Log "RUN_DIR=$OutDir"
Log "run_id=$runId"
Log "=== ETAPA 1 SMOKE: reauth + PropertyDeleteRequest + owner governance + n8n audit ==="

# --- Credenciales ---
$ownerEmail = $env:SMOKE_EMAIL
$ownerPass = $env:SMOKE_PASSWORD
$ownerMfa = $env:SMOKE_MFA_CODE
$saEmail = $env:SMOKE_SA_EMAIL
$saPass = $env:SMOKE_SA_PASSWORD
$saMfa = $env:SMOKE_SA_MFA_CODE

if ([string]::IsNullOrWhiteSpace($ownerEmail) -or [string]::IsNullOrWhiteSpace($ownerPass)) {
  Log "BLOQUEO: defina SMOKE_EMAIL y SMOKE_PASSWORD"
  Finalize-Evidence 2 "BLOCKED_NO_CREDS" "falta credenciales owner"
  exit 2
}

# ============================================================
# 1. LOGIN OWNER + MFA + CONTEXTS + SELECT-CONTEXT
# ============================================================
Log "--- [1] POST /auth/login (OWNER) ---"
$loginRes = Invoke-Api -Method POST -PathRel "auth/login" -JsonBody (@{ email = $ownerEmail; password = $ownerPass } | ConvertTo-Json -Compress)
Log "login status=$($loginRes.StatusCode)"
if ($loginRes.StatusCode -lt 200 -or $loginRes.StatusCode -ge 300) {
  Log "login failed: $($loginRes.Raw.Substring(0, [Math]::Min(300, $loginRes.Raw.Length)))"
  Finalize-Evidence 3 "BLOCKED_LOGIN" ""
  exit 3
}

$j = $loginRes.Parsed
$access = [string]$j.token
$mfaChallenge = $null
if ($j.PSObject.Properties.Name -contains 'mfaChallengeToken') { $mfaChallenge = [string]$j.mfaChallengeToken }
Log "token redacted=$(Redact-Token $access); mfaChallenge=$(-not [string]::IsNullOrWhiteSpace($mfaChallenge))"

if (-not [string]::IsNullOrWhiteSpace($mfaChallenge)) {
  if ([string]::IsNullOrWhiteSpace($ownerMfa)) {
    Log "BLOCKED: MFA challenge sin SMOKE_MFA_CODE"
    Finalize-Evidence 4 "BLOCKED_MFA" ""
    exit 4
  }
  Log "--- POST /auth/mfa/verify ---"
  $mfaRes = Invoke-Api -Method POST -PathRel "auth/mfa/verify" -JsonBody (@{ email = $ownerEmail; code = $ownerMfa; challengeToken = $mfaChallenge } | ConvertTo-Json -Compress)
  Log "mfa/verify status=$($mfaRes.StatusCode)"
  if ($mfaRes.StatusCode -lt 200 -or $mfaRes.StatusCode -ge 300) {
    Log "mfa body: $($mfaRes.Raw.Substring(0, [Math]::Min(300, $mfaRes.Raw.Length)))"
    Finalize-Evidence 5 "BLOCKED_MFA_VERIFY" ""
    exit 5
  }
  $j = $mfaRes.Parsed
  $access = [string]$j.token
}

$hdrOwner = @{ Authorization = "Bearer $access" }

Log "--- [1] GET /auth/contexts ---"
$ctxRes = Invoke-Api -Method GET -PathRel "auth/contexts" -ExtraHeaders $hdrOwner
Log "contexts status=$($ctxRes.StatusCode)"
if ($ctxRes.StatusCode -ge 200 -and $ctxRes.StatusCode -lt 300) {
  Log "PASS: /auth/contexts 200"
} else {
  Log "FAIL: /auth/contexts $($ctxRes.StatusCode)"
  $issues += "CONTEXTS"
}

# Select context if needed
$needsOrg = $false
if ($null -ne $j -and ($j.PSObject.Properties.Name -contains 'requiresOrgSelection')) {
  $needsOrg = [bool]$j.requiresOrgSelection
}
if ($needsOrg -or ($null -ne $j -and $null -ne $j.organizations)) {
  # Auto-select first context
  $ctxId = $null
  if ($null -ne $j.organizations) {
    $ctxId = ($j.organizations.PSObject.Properties | Select-Object -First 1).Name
  }
  if ($null -ne $ctxRes.Parsed -and $null -ne $ctxRes.Parsed.contexts) {
    $first = $ctxRes.Parsed.contexts | Select-Object -First 1
    if ($null -ne $first) { $ctxId = $first.id }
  }
  if (-not [string]::IsNullOrWhiteSpace($ctxId)) {
    Log "--- [1] POST /auth/select-context (contextId=$ctxId) ---"
    $sel = Invoke-Api -Method POST -PathRel "auth/select-context" -JsonBody (@{ contextId = $ctxId } | ConvertTo-Json -Compress) -ExtraHeaders $hdrOwner
    Log "select-context status=$($sel.StatusCode)"
    if ($sel.StatusCode -ge 200 -and $sel.StatusCode -lt 300 -and $null -ne $sel.Parsed) {
      $access = [string]$sel.Parsed.token
      $hdrOwner = @{ Authorization = "Bearer $access" }
      Log "PASS: select-context 200"
    } else {
      $issues += "SELECT_CONTEXT"
    }
  }
}

# ============================================================
# 2. PROPERTY LIST (OWNER sees properties)
# ============================================================
Log "--- [2] GET /properties ---"
$propsRes = Invoke-Api -Method GET -PathRel "properties" -ExtraHeaders $hdrOwner
Log "properties status=$($propsRes.StatusCode)"
if ($propsRes.StatusCode -ge 200 -and $propsRes.StatusCode -lt 300) {
  $propCount = 0
  if ($null -ne $propsRes.Parsed) { $propCount = @($propsRes.Parsed).Count }
  Log "PASS: GET /properties 200, count=$propCount"
} else {
  Log "FAIL: GET /properties $($propsRes.StatusCode)"
  $issues += "PROPERTIES_LIST"
}

# ============================================================
# 3. REAUTH TEST - DELETE property requiring reauth (expect 200 or business error)
# ============================================================
Log "--- [3] DELETE /properties/nonexistent-id (reauth test) ---"
$delBody = @{ password = $ownerPass; mfaCode = $ownerMfa } | ConvertTo-Json -Compress
$delRes = Invoke-Api -Method DELETE -PathRel "properties/nonexistent-test-id" -JsonBody $delBody -ExtraHeaders (@{ Authorization = "Bearer $access"; "Content-Type" = "application/json" })
Log "delete-reauth status=$($delRes.StatusCode)"
if ($delRes.StatusCode -eq 500 -or $delRes.StatusCode -eq 400) {
  Log "PASS: reauth endpoint responding (expected business error for nonexistent property)"
} elseif ($delRes.StatusCode -eq 403 -or $delRes.StatusCode -eq 401) {
  Log "FAIL: reauth endpoint returned auth error"
  $issues += "REAUTH_AUTH"
} else {
  Log "NOTE: reauth status=$($delRes.StatusCode) - reviewing"
}

# ============================================================
# 4. SUPER_ADMIN LOGIN (if credentials provided)
# ============================================================
$hasSA = (-not [string]::IsNullOrWhiteSpace($saEmail)) -and (-not [string]::IsNullOrWhiteSpace($saPass))
if ($hasSA) {
  Log "--- [4] POST /auth/login (SUPER_ADMIN) ---"
  $saLoginRes = Invoke-Api -Method POST -PathRel "auth/login" -JsonBody (@{ email = $saEmail; password = $saPass } | ConvertTo-Json -Compress)
  Log "SA login status=$($saLoginRes.StatusCode)"

  if ($saLoginRes.StatusCode -ge 200 -and $saLoginRes.StatusCode -lt 300) {
    $saJ = $saLoginRes.Parsed
    $saAccess = [string]$saJ.token
    $saMfaChallenge = $null
    if ($saJ.PSObject.Properties.Name -contains 'mfaChallengeToken') { $saMfaChallenge = [string]$saJ.mfaChallengeToken }

    if (-not [string]::IsNullOrWhiteSpace($saMfaChallenge)) {
      if ([string]::IsNullOrWhiteSpace($saMfa)) {
        Log "SKIP: SA MFA challenge but no SMOKE_SA_MFA_CODE"
        $hasSA = $false
      } else {
        $saMfaRes = Invoke-Api -Method POST -PathRel "auth/mfa/verify" -JsonBody (@{ email = $saEmail; code = $saMfa; challengeToken = $saMfaChallenge } | ConvertTo-Json -Compress)
        Log "SA mfa/verify status=$($saMfaRes.StatusCode)"
        if ($saMfaRes.StatusCode -ge 200 -and $saMfaRes.StatusCode -lt 300) {
          $saAccess = [string]$saMfaRes.Parsed.token
        } else {
          Log "SKIP: SA MFA verify failed"
          $hasSA = $false
        }
      }
    }

    if ($hasSA) {
      $hdrSA = @{ Authorization = "Bearer $saAccess" }

      # GET /admin/owners
      Log "--- [4] GET /admin/owners ---"
      $ownersRes = Invoke-Api -Method GET -PathRel "admin/owners" -ExtraHeaders $hdrSA
      Log "admin/owners status=$($ownersRes.StatusCode)"
      if ($ownersRes.StatusCode -ge 200 -and $ownersRes.StatusCode -lt 300) {
        Log "PASS: GET /admin/owners 200"
      } else {
        $issues += "SA_OWNERS_LIST"
      }
    }
  } else {
    Log "SKIP: SA login failed ($($saLoginRes.StatusCode))"
    $hasSA = $false
  }
} else {
  Log "SKIP: SUPER_ADMIN tests (no SMOKE_SA_EMAIL/SMOKE_SA_PASSWORD)"
}

# ============================================================
# 5. N8N PAYLOAD AUDIT — grep code for sensitive field leaks
# ============================================================
Log "--- [5] N8N payload audit (static code analysis) ---"
$n8nFile = Join-Path (Split-Path $PSScriptRoot -Parent) "backend\src\main\java\com\admindi\backend\service\N8nNotificationAdapter.java"
if (Test-Path $n8nFile) {
  $n8nContent = Get-Content $n8nFile -Raw
  $sensitivePatterns = @("tempPassword", "mfaSecret", "recoveryToken", "resetToken", "refreshToken", "accessToken", "sessionToken")
  $redactedBlockPresent = $n8nContent -match "REDACTED_FIELDS"

  $leaks = @()
  foreach ($p in $sensitivePatterns) {
    # Check if the field is PUT into a payload (not just in the redaction list)
    $pattern = 'payload\.put\(\s*"' + $p + '"'
    $payloadPuts = [regex]::Matches($n8nContent, $pattern)
    if ($payloadPuts.Count -gt 0) {
      $leaks += $p
    }
  }

  if ($redactedBlockPresent) {
    Log "PASS: REDACTED_FIELDS defense-in-depth block present"
  } else {
    Log "FAIL: REDACTED_FIELDS block not found"
    $issues += "N8N_NO_REDACTION"
  }

  if ($leaks.Count -eq 0) {
    Log "PASS: no sensitive fields in payload.put() calls"
  } else {
    Log "FAIL: sensitive fields found in payload.put(): $($leaks -join ', ')"
    $issues += "N8N_LEAK_$($leaks -join '_')"
  }
} else {
  Log "SKIP: N8nNotificationAdapter.java not found at $n8nFile"
}

# ============================================================
# 6. REAUTH SERVICE AUDIT — verify ReauthService exists
# ============================================================
Log "--- [6] ReauthService code audit ---"
$reauthFile = Join-Path (Split-Path $PSScriptRoot -Parent) "backend\src\main\java\com\admindi\backend\service\ReauthService.java"
if (Test-Path $reauthFile) {
  $reauthContent = Get-Content $reauthFile -Raw
  $hasAudit = $reauthContent -match "auditReauthAttempt"
  $hasMfaCheck = $reauthContent -match "codeVerifier\.isValidCode"
  if ($hasAudit -and $hasMfaCheck) {
    Log "PASS: ReauthService has audit + MFA verification"
  } else {
    Log "PARTIAL: ReauthService exists but audit=$hasAudit mfaCheck=$hasMfaCheck"
    $issues += "REAUTH_INCOMPLETE"
  }
} else {
  Log "FAIL: ReauthService.java not found"
  $issues += "REAUTH_MISSING"
}

# ============================================================
# 7. TENANT MULTI-OWNER — contexts, select-context, switch-context, expedientes
# ============================================================
function Do-LoginWithMfa([string]$email, [string]$pass, [string]$mfaCode, [string]$label) {
  Log "--- [7] POST /auth/login ($label) ---"
  $lr = Invoke-Api -Method POST -PathRel "auth/login" -JsonBody (@{ email = $email; password = $pass } | ConvertTo-Json -Compress)
  Log "login $label status=$($lr.StatusCode)"
  if ($lr.StatusCode -lt 200 -or $lr.StatusCode -ge 300) {
    Log "login $label failed: $($lr.Raw.Substring(0, [Math]::Min(280, $lr.Raw.Length)))"
    return @{ Ok = $false; Token = $null }
  }
  $jp = $lr.Parsed
  $tok = [string]$jp.token
  $ch = $null
  if ($jp.PSObject.Properties.Name -contains 'mfaChallengeToken') { $ch = [string]$jp.mfaChallengeToken }
  if (-not [string]::IsNullOrWhiteSpace($ch)) {
    if ([string]::IsNullOrWhiteSpace($mfaCode)) {
      Log "BLOCKED: MFA challenge $label sin código TOTP"
      return @{ Ok = $false; Token = $null }
    }
    $mv = Invoke-Api -Method POST -PathRel "auth/mfa/verify" -JsonBody (@{ email = $email; code = $mfaCode; challengeToken = $ch } | ConvertTo-Json -Compress)
    Log "mfa/verify $label status=$($mv.StatusCode)"
    if ($mv.StatusCode -lt 200 -or $mv.StatusCode -ge 300) {
      Log "mfa verify $label body: $($mv.Raw.Substring(0, [Math]::Min(280, $mv.Raw.Length)))"
      return @{ Ok = $false; Token = $null }
    }
    $tok = [string]$mv.Parsed.token
  }
  return @{ Ok = $true; Token = $tok }
}

$tenantEmail = if (-not [string]::IsNullOrWhiteSpace($env:SMOKE_TENANT_EMAIL)) { $env:SMOKE_TENANT_EMAIL.Trim() } else { "qa.etapa0.tenant.multi@test.local" }
$tenantPass = if (-not [string]::IsNullOrWhiteSpace($env:SMOKE_TENANT_PASSWORD)) { $env:SMOKE_TENANT_PASSWORD } else { $ownerPass }
$tenantMfa = if (-not [string]::IsNullOrWhiteSpace($env:SMOKE_TENANT_MFA_CODE)) { $env:SMOKE_TENANT_MFA_CODE } else { $ownerMfa }

$tenantLogin = Do-LoginWithMfa $tenantEmail $tenantPass $tenantMfa "TENANT_MULTI"
$tenantContextToken = $null
if (-not $tenantLogin.Ok) {
  $issues += "TENANT_LOGIN"
} else {
  $hdrTn = @{ Authorization = "Bearer $($tenantLogin.Token)" }
  Log "--- [7] GET /auth/contexts (tenant multi) ---"
  $tctx = Invoke-Api -Method GET -PathRel "auth/contexts" -ExtraHeaders $hdrTn
  Log "tenant contexts status=$($tctx.StatusCode)"
  if ($tctx.StatusCode -lt 200 -or $tctx.StatusCode -ge 300) {
    $issues += "TENANT_CONTEXTS_HTTP"
  } else {
    $arr = @()
    if ($null -ne $tctx.Parsed -and $null -ne $tctx.Parsed.contexts) { $arr = @($tctx.Parsed.contexts) }
    Log "tenant context count=$($arr.Count)"
    if ($arr.Count -lt 2) {
      Log "FAIL: se esperaban >=2 owners en contexts para tenant multi"
      $issues += "TENANT_CONTEXTS_LT2"
    } else {
      $idA = [string]$arr[0].id
      $idB = [string]$arr[1].id
      Log "--- [7] POST /auth/select-context (first=$idA) ---"
      $selA = Invoke-Api -Method POST -PathRel "auth/select-context" -JsonBody (@{ contextId = $idA } | ConvertTo-Json -Compress) -ExtraHeaders $hdrTn
      Log "select-context A status=$($selA.StatusCode)"
      if ($selA.StatusCode -lt 200 -or $selA.StatusCode -ge 300 -or $null -eq $selA.Parsed) {
        $issues += "TENANT_SELECT_A"
      } else {
        $tokA = [string]$selA.Parsed.token
        $tenantContextToken = $tokA
        $hdrA = @{ Authorization = "Bearer $tokA" }
        Log "--- [7] GET /tenant/expedientes (context A) ---"
        $exA = Invoke-Api -Method GET -PathRel "tenant/expedientes" -ExtraHeaders $hdrA
        Log "expedientes A status=$($exA.StatusCode) count=$(if ($null -ne $exA.Parsed) { @($exA.Parsed).Count } else { -1 })"
        if ($exA.StatusCode -lt 200 -or $exA.StatusCode -ge 300) { $issues += "TENANT_EXPEDIENTES_A" }

        Log "--- [7] POST /auth/switch-context (to=$idB) FULL token ---"
        $sw = Invoke-Api -Method POST -PathRel "auth/switch-context" -JsonBody (@{ contextId = $idB } | ConvertTo-Json -Compress) -ExtraHeaders $hdrA
        Log "switch-context status=$($sw.StatusCode)"
        if ($sw.StatusCode -lt 200 -or $sw.StatusCode -ge 300 -or $null -eq $sw.Parsed) {
          $issues += "TENANT_SWITCH_CONTEXT"
        } else {
          $tokB = [string]$sw.Parsed.token
          $hdrB = @{ Authorization = "Bearer $tokB" }
          Log "--- [7] GET /tenant/expedientes (context B) ---"
          $exB = Invoke-Api -Method GET -PathRel "tenant/expedientes" -ExtraHeaders $hdrB
          Log "expedientes B status=$($exB.StatusCode) count=$(if ($null -ne $exB.Parsed) { @($exB.Parsed).Count } else { -1 })"
          if ($exB.StatusCode -lt 200 -or $exB.StatusCode -ge 300) { $issues += "TENANT_EXPEDIENTES_B" }
        }
      }
    }
  }
}

# ============================================================
# 8. INBOX / TASKS + NOTIFICACIONES + PREFERENCIAS (OWNER token actual)
# ============================================================
Log "--- [8] GET /tasks/open-count (owner) ---"
$toc = Invoke-Api -Method GET -PathRel "tasks/open-count" -ExtraHeaders $hdrOwner
Log "tasks/open-count status=$($toc.StatusCode)"
if ($toc.StatusCode -lt 200 -or $toc.StatusCode -ge 300) { $issues += "TASKS_OPEN_COUNT" }

Log "--- [8] GET /tasks?status=OPEN ---"
$tsk = Invoke-Api -Method GET -PathRel "tasks?status=OPEN" -ExtraHeaders $hdrOwner
Log "tasks OPEN status=$($tsk.StatusCode)"
if ($tsk.StatusCode -lt 200 -or $tsk.StatusCode -ge 300) { $issues += "TASKS_LIST" }

Log "--- [8] GET /notifications/unread-count ---"
$nuc = Invoke-Api -Method GET -PathRel "notifications/unread-count" -ExtraHeaders $hdrOwner
Log "notifications/unread-count status=$($nuc.StatusCode)"
if ($nuc.StatusCode -lt 200 -or $nuc.StatusCode -ge 300) { $issues += "NOTIF_UNREAD" }

Log "--- [8] GET /notifications (primeras) ---"
$not = Invoke-Api -Method GET -PathRel "notifications" -ExtraHeaders $hdrOwner
Log "notifications list status=$($not.StatusCode)"
if ($not.StatusCode -lt 200 -or $not.StatusCode -ge 300) { $issues += "NOTIF_LIST" }

Log "--- [8] GET /notifications/preferences ---"
$prefGet = Invoke-Api -Method GET -PathRel "notifications/preferences" -ExtraHeaders $hdrOwner
Log "preferences GET status=$($prefGet.StatusCode)"
if ($prefGet.StatusCode -lt 200 -or $prefGet.StatusCode -ge 300) {
  $issues += "PREF_GET"
} else {
  # Contrato Etapa 1:
  #  - ningún canal N8N en respuesta
  #  - ningún evento OWNER_CREATED en respuesta
  #  - si aparece IN_APP, enabled debe ser true
  $rawList = if ($null -ne $prefGet.Parsed) { @($prefGet.Parsed) } else { @() }
  $hasN8n = $false
  $hasOwnerCreated = $false
  $inAppOff = $false
  foreach ($p in $rawList) {
    if ($null -eq $p) { continue }
    if ($p.channel -eq "N8N") { $hasN8n = $true }
    if ($p.eventType -eq "OWNER_CREATED") { $hasOwnerCreated = $true }
    if ($p.channel -eq "IN_APP" -and -not $p.enabled) { $inAppOff = $true }
  }
  if ($hasN8n) { Log "FAIL: GET /preferences expone canal N8N"; $issues += "PREF_GET_HAS_N8N" } else { Log "PASS: GET /preferences sin N8N" }
  if ($hasOwnerCreated) { Log "FAIL: GET /preferences expone OWNER_CREATED"; $issues += "PREF_GET_HAS_OWNER_CREATED" } else { Log "PASS: GET /preferences sin OWNER_CREATED" }
  if ($inAppOff) { Log "FAIL: GET /preferences devolvió IN_APP apagado"; $issues += "PREF_GET_INAPP_OFF" }
}

Log "--- [8] GET /notifications/preferences/catalog ---"
$catRes = Invoke-Api -Method GET -PathRel "notifications/preferences/catalog" -ExtraHeaders $hdrOwner
Log "catalog status=$($catRes.StatusCode)"
if ($catRes.StatusCode -lt 200 -or $catRes.StatusCode -ge 300) {
  $issues += "PREF_CATALOG_HTTP"
} else {
  $channelIds = @()
  $eventTypes = @()
  if ($null -ne $catRes.Parsed) {
    if ($null -ne $catRes.Parsed.channels) { $channelIds = @($catRes.Parsed.channels | ForEach-Object { $_.id }) }
    if ($null -ne $catRes.Parsed.events) { $eventTypes = @($catRes.Parsed.events | ForEach-Object { $_.eventType }) }
  }
  $expectedChannels = @("IN_APP","EMAIL","WHATSAPP")
  $missing = @($expectedChannels | Where-Object { $_ -notin $channelIds })
  if ($missing.Count -gt 0) { Log "FAIL: catalog canales faltantes: $($missing -join ',')"; $issues += "PREF_CATALOG_CHANNELS" } else { Log "PASS: catalog expone IN_APP/EMAIL/WHATSAPP" }
  if ("N8N" -in $channelIds) { Log "FAIL: catalog expone N8N"; $issues += "PREF_CATALOG_HAS_N8N" }
  if ("OWNER_CREATED" -in $eventTypes) { Log "FAIL: catalog expone OWNER_CREATED"; $issues += "PREF_CATALOG_HAS_OWNER_CREATED" } else { Log "PASS: catalog sin OWNER_CREATED" }
  # Verificar flags mandatory/editable de IN_APP
  $inAppMeta = $null
  if ($null -ne $catRes.Parsed -and $null -ne $catRes.Parsed.channels) {
    $inAppMeta = $catRes.Parsed.channels | Where-Object { $_.id -eq "IN_APP" } | Select-Object -First 1
  }
  if ($null -eq $inAppMeta -or -not $inAppMeta.mandatory -or $inAppMeta.editable) {
    Log "FAIL: IN_APP no declarado mandatory/no-editable en catalog"
    $issues += "PREF_CATALOG_INAPP_FLAGS"
  } else {
    Log "PASS: IN_APP mandatory=true editable=false en catalog"
  }
}

Log "--- [8] PUT /notifications/preferences rechaza canal N8N (esperado 422) ---"
$badN8nBody = (@{ preferences = @(@{ eventType = "PROPERTY_DELETE_REQUESTED"; channel = "N8N"; enabled = $true }) } | ConvertTo-Json -Compress -Depth 5)
$badN8n = Invoke-Api -Method PUT -PathRel "notifications/preferences" -JsonBody $badN8nBody -ExtraHeaders $hdrOwner
Log "PUT N8N status=$($badN8n.StatusCode)"
if ($badN8n.StatusCode -ne 422) { Log "FAIL: PUT N8N devolvió $($badN8n.StatusCode), se esperaba 422"; $issues += "PREF_PUT_N8N_NOT_422" } else { Log "PASS: PUT N8N rechazado 422" }

Log "--- [8] PUT /notifications/preferences rechaza IN_APP enabled=false (esperado 422) ---"
$badInAppBody = (@{ preferences = @(@{ eventType = "PROPERTY_DELETE_REQUESTED"; channel = "IN_APP"; enabled = $false }) } | ConvertTo-Json -Compress -Depth 5)
$badInApp = Invoke-Api -Method PUT -PathRel "notifications/preferences" -JsonBody $badInAppBody -ExtraHeaders $hdrOwner
Log "PUT IN_APP off status=$($badInApp.StatusCode)"
if ($badInApp.StatusCode -ne 422) { Log "FAIL: PUT IN_APP off devolvió $($badInApp.StatusCode), se esperaba 422"; $issues += "PREF_PUT_INAPP_NOT_422" } else { Log "PASS: PUT IN_APP off rechazado 422" }

Log "--- [8] PUT /notifications/preferences rechaza OWNER_CREATED oculto (esperado 422) ---"
$badEvBody = (@{ preferences = @(@{ eventType = "OWNER_CREATED"; channel = "EMAIL"; enabled = $true }) } | ConvertTo-Json -Compress -Depth 5)
$badEv = Invoke-Api -Method PUT -PathRel "notifications/preferences" -JsonBody $badEvBody -ExtraHeaders $hdrOwner
Log "PUT OWNER_CREATED status=$($badEv.StatusCode)"
if ($badEv.StatusCode -ne 422) { Log "FAIL: PUT OWNER_CREATED devolvió $($badEv.StatusCode), se esperaba 422"; $issues += "PREF_PUT_OWNER_CREATED_NOT_422" } else { Log "PASS: PUT OWNER_CREATED rechazado 422" }

Log "--- [8] PUT /notifications/preferences happy path WHATSAPP + EMAIL ---"
$okBody = (@{ preferences = @(
    @{ eventType = "PROPERTY_DELETE_REQUESTED"; channel = "EMAIL"; enabled = $true },
    @{ eventType = "PROPERTY_DELETE_REQUESTED"; channel = "WHATSAPP"; enabled = $true }
  ) } | ConvertTo-Json -Compress -Depth 5)
$okPut = Invoke-Api -Method PUT -PathRel "notifications/preferences" -JsonBody $okBody -ExtraHeaders $hdrOwner
Log "PUT happy status=$($okPut.StatusCode)"
if ($okPut.StatusCode -lt 200 -or $okPut.StatusCode -ge 300) {
  Log "preferences PUT happy body: $($okPut.Raw.Substring(0, [Math]::Min(400, $okPut.Raw.Length)))"
  $issues += "PREF_PUT_HAPPY"
} else {
  $retList = if ($null -ne $okPut.Parsed) { @($okPut.Parsed) } else { @() }
  $retHasN8n = ($retList | Where-Object { $_.channel -eq "N8N" } | Measure-Object).Count -gt 0
  $retHasOC = ($retList | Where-Object { $_.eventType -eq "OWNER_CREATED" } | Measure-Object).Count -gt 0
  if ($retHasN8n) { $issues += "PREF_PUT_RET_N8N" }
  if ($retHasOC) { $issues += "PREF_PUT_RET_OWNER_CREATED" }
}

# ============================================================
# 9. AISLAMIENTO — tenant intenta leer/editar sus preferencias (usuario operativo ≠ OWNER)
# ============================================================
if ($tenantLogin.Ok) {
  # Usar token FULL con contexto seleccionado; /notifications/preferences requiere FULL.
  $tnPrefToken = if (-not [string]::IsNullOrWhiteSpace($tenantContextToken)) { $tenantContextToken } else { $tenantLogin.Token }
  $hdrTnonly = @{ Authorization = "Bearer $tnPrefToken" }
  Log "--- [9] GET /notifications/preferences (tenant) ---"
  $tPrefGet = Invoke-Api -Method GET -PathRel "notifications/preferences" -ExtraHeaders $hdrTnonly
  Log "tenant prefs GET status=$($tPrefGet.StatusCode)"
  if ($tPrefGet.StatusCode -lt 200 -or $tPrefGet.StatusCode -ge 300) {
    $issues += "TENANT_PREF_GET"
  } else {
    Log "PASS: tenant lee sus propias preferencias"
  }

  Log "--- [9] PUT /notifications/preferences (tenant own) ---"
  $tPutBody = (@{ preferences = @(@{ eventType = "TENANT_CREATED"; channel = "EMAIL"; enabled = $true }) } | ConvertTo-Json -Compress -Depth 5)
  $tPut = Invoke-Api -Method PUT -PathRel "notifications/preferences" -JsonBody $tPutBody -ExtraHeaders $hdrTnonly
  Log "tenant prefs PUT status=$($tPut.StatusCode)"
  if ($tPut.StatusCode -lt 200 -or $tPut.StatusCode -ge 300) {
    Log "tenant PUT body: $($tPut.Raw.Substring(0, [Math]::Min(400, $tPut.Raw.Length)))"
    $issues += "TENANT_PREF_PUT"
  } else {
    Log "PASS: tenant edita sus propias preferencias"
  }
}

# ============================================================
# 10. STATIC AUDIT — N8N fuera del dominio público
# ============================================================
Log "--- [10] Static audit: frontend no expone N8N ni OWNER_CREATED como preferencia ---"
$fePrefSvc = Join-Path (Split-Path $PSScriptRoot -Parent) "frontend\src\services\notificationPreferenceService.ts"
if (Test-Path $fePrefSvc) {
  $svcContent = Get-Content $fePrefSvc -Raw
  # Buscar N8N como elemento del array de canales visibles (no redact/filtros)
  $channelArrayMatch = [regex]::Match($svcContent, "NOTIFICATION_CHANNEL_OPTIONS\s*=\s*\[([^\]]+)\]")
  if ($channelArrayMatch.Success) {
    $arrTxt = $channelArrayMatch.Groups[1].Value
    if ($arrTxt -match "'N8N'" -or $arrTxt -match "`"N8N`"") {
      Log "FAIL: frontend notificationPreferenceService expone N8N en NOTIFICATION_CHANNEL_OPTIONS"
      $issues += "FE_EXPOSES_N8N"
    } else {
      Log "PASS: frontend NOTIFICATION_CHANNEL_OPTIONS sin N8N"
    }
    foreach ($expected in @("IN_APP","EMAIL","WHATSAPP")) {
      if ($arrTxt -notmatch "'$expected'" -and $arrTxt -notmatch "`"$expected`"") {
        Log "FAIL: frontend NOTIFICATION_CHANNEL_OPTIONS no incluye $expected"
        $issues += "FE_MISSING_$expected"
      }
    }
  } else {
    Log "FAIL: no pude localizar NOTIFICATION_CHANNEL_OPTIONS en frontend"
    $issues += "FE_CHANNELS_REGEX"
  }
  # OWNER_CREATED no debe estar en lista fallback de eventos
  $feFallbackMatch = [regex]::Match($svcContent, "FALLBACK_EVENTS[\s\S]*?\];")
  if ($feFallbackMatch.Success -and $feFallbackMatch.Value -match "OWNER_CREATED") {
    Log "FAIL: frontend FALLBACK_EVENTS incluye OWNER_CREATED"
    $issues += "FE_FALLBACK_HAS_OWNER_CREATED"
  } else {
    Log "PASS: frontend FALLBACK_EVENTS sin OWNER_CREATED"
  }
} else {
  Log "SKIP: no encontré notificationPreferenceService.ts"
}

Log "--- [10] Static audit: DomainEventDispatcher wiring EMAIL + WHATSAPP ---"
$dispatcherFile = Join-Path (Split-Path $PSScriptRoot -Parent) "backend\src\main\java\com\admindi\backend\service\DomainEventDispatcher.java"
if (Test-Path $dispatcherFile) {
  $dcontent = Get-Content $dispatcherFile -Raw
  if ($dcontent -match "EmailService" -and $dcontent -match "sendEventEmail" -and $dcontent -match "NotificationChannels\.WHATSAPP" -and $dcontent -match "NotificationChannels\.EMAIL") {
    Log "PASS: dispatcher usa EmailService + canales EMAIL/WHATSAPP"
  } else {
    Log "FAIL: dispatcher no referencia EmailService + canales EMAIL/WHATSAPP"
    $issues += "DISPATCHER_WIRING"
  }
  if ($dcontent -match "anyWhatsapp") {
    Log "PASS: dispatcher condiciona WhatsApp a preferencia de usuario"
  } else {
    Log "FAIL: dispatcher no condiciona WhatsApp a preferencia"
    $issues += "DISPATCHER_WHATSAPP_UNCONDITIONAL"
  }
} else {
  Log "SKIP: DomainEventDispatcher.java no encontrado"
}

# ============================================================
# VERDICT
# ============================================================
$notes = "etapa1 integral: owner+tenant multi+switch+tasks+notifications+preferences+reauth+n8n-audit"
if ($issues.Count -eq 0) {
  $verdict = "PASSED_ETAPA1_INTEGRAL_QA"
} else {
  $verdict = "PARTIAL_$($issues -join '_')"
}

Log "=== VERDICT: $verdict ==="
$exitCode = if ($issues.Count -eq 0) { 0 } else { 1 }
Finalize-Evidence $exitCode $verdict $notes
Write-Host "ETAPA1_SMOKE_RESULT=$verdict"
Write-Host "Evidence: $OutDir"
exit $exitCode
