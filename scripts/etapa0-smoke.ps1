<#
.SYNOPSIS
  Smoke reproducible Etapa 0 — auth + context + refresh + reuse (ADMINDI).
.NOTES
  SMOKE_BASE_URL default: http://localhost:8080/api
  SMOKE_EMAIL / SMOKE_PASSWORD — obligatorio.
  SMOKE_MFA_CODE — si login devuelve MFA_CHALLENGE.
  SMOKE_CONTEXT_ID — ownerId para select/switch cuando requiresOrgSelection=true.
  Archivo: SMOKE_TENANT_EMAIL/PASSWORD + (SMOKE_OWNER_TOKEN_BEARER o SMOKE_ARCHIVE_OWNER_*). SMOKE_TENANT_PROFILE_ID opcional: si falta, el script hace GET /tenants como owner archive y resuelve el id por email.
  Owner archive con MFA: definir SMOKE_ARCHIVE_MFA_CODE (6 dígitos TOTP) o SMOKE_OWNER_TOKEN_BEARER ya verificado.
  SMOKE_FULL_ETAPA0=1 + SMOKE_QA_MFA_CODE: MFA superadmin QA + GET contexts staff (prop admin + accountant). Password QA compartida SMOKE_QA_SHARED_PASSWORD (default QaEtapa0-Test-2024!).
  Prop admin + Alpha: el script resuelve contextId con GET /auth/contexts (nombre por defecto "QA Owner Alpha"; override SMOKE_PROPADMIN_ALPHA_CONTEXT_NAME o id explícito SMOKE_OWNER_ALPHA_ID).
  Primer parámetro o SMOKE_OUT: directorio de evidencia (default qa-evidence/2026-04-15/etapa-0).
  POST /auth/logout; probe post-logout; post-archive login tenant (cuenta desactivada).
#>
param(
  [string]$OutDir = $(if ($env:SMOKE_OUT) { $env:SMOKE_OUT } else { Join-Path (Split-Path $PSScriptRoot -Parent) "qa-evidence\2026-04-15\etapa-0" })
)

$ErrorActionPreference = "Stop"
$base = ($env:SMOKE_BASE_URL)
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

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
Log "SMOKE_BASE_URL=$base"
Log "OUT_DIR=$OutDir"

# --- Redis ---
$redisOk = $false
try {
  $tcp = Test-NetConnection -ComputerName localhost -Port 6379 -WarningAction SilentlyContinue
  $redisOk = [bool]$tcp.TcpTestSucceeded
  Log "Redis localhost:6379 TcpTestSucceeded=$redisOk"
}
catch { Log "Redis check error: $($_.Exception.Message)" }

$encKeySet = -not [string]::IsNullOrWhiteSpace($env:ENCRYPTION_KEY)
Log "ENCRYPTION_KEY en entorno smoke=$encKeySet (prod sin clave = BLOQUEO per EncryptionService)"

Log "Storage: local uploads por defecto (application.yml); R2 = documentar por entorno (no validado en smoke)."

$email = $env:SMOKE_EMAIL
$pass = $env:SMOKE_PASSWORD
if ([string]::IsNullOrWhiteSpace($email) -or [string]::IsNullOrWhiteSpace($pass)) {
  Log "BLOQUEO: defina SMOKE_EMAIL y SMOKE_PASSWORD."
  $log | Set-Content -Encoding utf8 (Join-Path $OutDir "SMOKE_HTTP.log")
  "SMOKE_RESULT=BLOCKED_NO_CREDS" | Set-Content -Encoding utf8 (Join-Path $OutDir "SMOKE_RESULT.txt")
  exit 2
}

# --- Login ---
Log "--- POST /auth/login ---"
$loginRes = Invoke-Api -Method POST -PathRel "auth/login" -JsonBody (@{ email = $email; password = $pass } | ConvertTo-Json -Compress)
Log "login status=$($loginRes.StatusCode)"
if ($loginRes.StatusCode -eq 429) {
  $lim = [Math]::Min(500, [Math]::Max(0, $loginRes.Raw.Length))
  Log "login rate limited (429). Limite backend: 5 POST /auth/login por IP cada 15 min (RateLimitInterceptor). Reiniciar JVM o esperar ventana."
  if ($lim -gt 0) { Log "login body (trunc): $($loginRes.Raw.Substring(0, $lim))" }
  $log | Set-Content -Encoding utf8 (Join-Path $OutDir "SMOKE_HTTP.log")
  "SMOKE_RESULT=BLOCKED_RATE_LIMIT_LOGIN" | Set-Content -Encoding utf8 (Join-Path $OutDir "SMOKE_RESULT.txt")
  exit 8
}
if ($loginRes.StatusCode -lt 200 -or $loginRes.StatusCode -ge 300) {
  $lim = [Math]::Min(500, [Math]::Max(0, $loginRes.Raw.Length))
  if ($lim -gt 0) { Log "login body (trunc): $($loginRes.Raw.Substring(0, $lim))" }
  $log | Set-Content -Encoding utf8 (Join-Path $OutDir "SMOKE_HTTP.log")
  "SMOKE_RESULT=BLOCKED_LOGIN" | Set-Content -Encoding utf8 (Join-Path $OutDir "SMOKE_RESULT.txt")
  exit 3
}

$j = $loginRes.Parsed
$access = [string]$j.token
$mfaChallenge = $null
if ($j.PSObject.Properties.Name -contains 'mfaChallengeToken') { $mfaChallenge = [string]$j.mfaChallengeToken }
$mfaSetup = $false
if ($j.PSObject.Properties.Name -contains 'mfaSetupRequired') { $mfaSetup = [bool]$j.mfaSetupRequired }
$refresh = [string]$j.refreshToken
Log "token redacted=$(Redact-Token $access); mfaChallenge=$(-not [string]::IsNullOrWhiteSpace($mfaChallenge)); mfaSetupRequired=$mfaSetup; refresh=$(Redact-Token $refresh)"

if (-not [string]::IsNullOrWhiteSpace($mfaChallenge)) {
  $otp = $env:SMOKE_MFA_CODE
  if ([string]::IsNullOrWhiteSpace($otp)) {
    Log "BLOCKED: MFA challenge sin SMOKE_MFA_CODE."
    $log | Set-Content -Encoding utf8 (Join-Path $OutDir "SMOKE_HTTP.log")
    "SMOKE_RESULT=BLOCKED_MFA_NEEDS_SMOKE_MFA_CODE" | Set-Content -Encoding utf8 (Join-Path $OutDir "SMOKE_RESULT.txt")
    exit 4
  }
  Log "--- POST /auth/mfa/verify ---"
  $mfaBody = (@{ email = $email; code = $otp; challengeToken = $mfaChallenge } | ConvertTo-Json -Compress)
  $mfaRes = Invoke-Api -Method POST -PathRel "auth/mfa/verify" -JsonBody $mfaBody
  Log "mfa/verify status=$($mfaRes.StatusCode)"
  if ($mfaRes.StatusCode -lt 200 -or $mfaRes.StatusCode -ge 300) {
    Log "mfa body trunc: $($mfaRes.Raw.Substring(0, [Math]::Min(400, [Math]::Max(0, $mfaRes.Raw.Length))))"
    $log | Set-Content -Encoding utf8 (Join-Path $OutDir "SMOKE_HTTP.log")
    "SMOKE_RESULT=BLOCKED_MFA_VERIFY" | Set-Content -Encoding utf8 (Join-Path $OutDir "SMOKE_RESULT.txt")
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

# --- GET contexts ---
Log "--- GET /auth/contexts ---"
$ctxRes = Invoke-Api -Method GET -PathRel "auth/contexts" -ExtraHeaders $hdrAuth
Log "contexts status=$($ctxRes.StatusCode)"
$ctxTrunc = if ($ctxRes.Raw.Length -gt 900) { $ctxRes.Raw.Substring(0, 900) + "…" } else { $ctxRes.Raw }
Log "contexts body trunc: $ctxTrunc"
if ($ctxRes.StatusCode -lt 200 -or $ctxRes.StatusCode -ge 300) {
  $log | Set-Content -Encoding utf8 (Join-Path $OutDir "SMOKE_HTTP.log")
  "SMOKE_RESULT=BLOCKED_CONTEXTS" | Set-Content -Encoding utf8 (Join-Path $OutDir "SMOKE_RESULT.txt")
  exit 6
}

$ctxId = $env:SMOKE_CONTEXT_ID
if ($needsOrg -and -not [string]::IsNullOrWhiteSpace($ctxId)) {
  Log "--- POST /auth/select-context ---"
  $sel = Invoke-Api -Method POST -PathRel "auth/select-context" -JsonBody (@{ contextId = $ctxId } | ConvertTo-Json -Compress) -ExtraHeaders $hdrAuth
  Log "select-context status=$($sel.StatusCode) trunc=$($sel.Raw.Substring(0, [Math]::Min(280, [Math]::Max(0, $sel.Raw.Length))))"
  if ($sel.StatusCode -ge 200 -and $sel.StatusCode -lt 300) {
    $j = $sel.Parsed
    $access = [string]$j.token
    $refresh = [string]$j.refreshToken
    $hdrAuth = @{ Authorization = "Bearer $access" }
  }
}
elseif ($needsOrg) {
  Log "SKIP select-context: requiresOrgSelection=true pero falta SMOKE_CONTEXT_ID"
}

if (-not [string]::IsNullOrWhiteSpace($ctxId)) {
  Log "--- POST /auth/switch-context ---"
  $sw = Invoke-Api -Method POST -PathRel "auth/switch-context" -JsonBody (@{ contextId = $ctxId } | ConvertTo-Json -Compress) -ExtraHeaders $hdrAuth
  Log "switch-context status=$($sw.StatusCode) trunc=$($sw.Raw.Substring(0, [Math]::Min(220, [Math]::Max(0, $sw.Raw.Length))))"
  if ($sw.StatusCode -ge 200 -and $sw.StatusCode -lt 300) {
    $j = $sw.Parsed
    $access = [string]$j.token
    $refresh = [string]$j.refreshToken
    $hdrAuth = @{ Authorization = "Bearer $access" }
  }
}
else {
  Log "SKIP switch-context: sin SMOKE_CONTEXT_ID"
}

# --- refresh ---
Log "--- POST /auth/refresh ---"
$oldRefresh = $refresh
$ctxForRefresh = $env:SMOKE_CONTEXT_ID
$refBodyObj = @{ refreshToken = $refresh }
if (-not [string]::IsNullOrWhiteSpace($ctxForRefresh)) { $refBodyObj["contextId"] = $ctxForRefresh }
$refRes = Invoke-Api -Method POST -PathRel "auth/refresh" -JsonBody ($refBodyObj | ConvertTo-Json -Compress)
Log "refresh status=$($refRes.StatusCode)"
if ($refRes.StatusCode -lt 200 -or $refRes.StatusCode -ge 300) {
  Log "refresh err: $($refRes.Raw.Substring(0, [Math]::Min(400, [Math]::Max(0, $refRes.Raw.Length))))"
  $log | Set-Content -Encoding utf8 (Join-Path $OutDir "SMOKE_HTTP.log")
  "SMOKE_RESULT=BLOCKED_REFRESH" | Set-Content -Encoding utf8 (Join-Path $OutDir "SMOKE_RESULT.txt")
  exit 7
}
$j = $refRes.Parsed
$access2 = [string]$j.token
$refresh2 = [string]$j.refreshToken
Log "post-refresh access=$(Redact-Token $access2); refresh=$(Redact-Token $refresh2)"

# --- reuse old refresh ---
Log "--- POST /auth/refresh (reuse old refresh, esperado 400) ---"
$reuse = Invoke-Api -Method POST -PathRel "auth/refresh" -JsonBody (@{ refreshToken = $oldRefresh; contextId = $ctxForRefresh } | ConvertTo-Json -Compress)
Log "reuse status=$($reuse.StatusCode) body=$($reuse.Raw.Substring(0, [Math]::Min(500, [Math]::Max(0, $reuse.Raw.Length))))"

Log "--- POST /auth/logout (Bearer access post-refresh) ---"
$hdrPostRefresh = @{ Authorization = "Bearer $access2" }
$lo = Invoke-Api -Method POST -PathRel "auth/logout" -JsonBody "{}" -ExtraHeaders $hdrPostRefresh
Log "logout status=$($lo.StatusCode)"

Log "--- GET /auth/contexts (mismo Bearer tras logout, esperado 401/403) ---"
$probeTok = Invoke-Api -Method GET -PathRel "auth/contexts" -ExtraHeaders $hdrPostRefresh
Log "post-logout contexts status=$($probeTok.StatusCode) body trunc=$($probeTok.Raw.Substring(0, [Math]::Min(200, [Math]::Max(0, $probeTok.Raw.Length))))"

$extraIssues = @()

# --- opcional tenant archivado (DELETE = baja operativa) ---
$archiveRan = $false
$archivePostLoginOk = $false
$ownerBearer = $env:SMOKE_OWNER_TOKEN_BEARER
if ([string]::IsNullOrWhiteSpace($ownerBearer) -and -not [string]::IsNullOrWhiteSpace($env:SMOKE_ARCHIVE_OWNER_EMAIL) -and -not [string]::IsNullOrWhiteSpace($env:SMOKE_ARCHIVE_OWNER_PASSWORD)) {
  Log "--- POST /auth/login (owner archive, para Bearer DELETE) ---"
  $ownLogin = Invoke-Api -Method POST -PathRel "auth/login" -JsonBody (@{ email = $env:SMOKE_ARCHIVE_OWNER_EMAIL; password = $env:SMOKE_ARCHIVE_OWNER_PASSWORD } | ConvertTo-Json -Compress)
  Log "owner-archive login status=$($ownLogin.StatusCode)"
  if ($ownLogin.StatusCode -ge 200 -and $ownLogin.StatusCode -lt 300 -and $null -ne $ownLogin.Parsed) {
    $oj = $ownLogin.Parsed
    $ownerBearer = [string]$oj.token
    $ownMfa = $null
    if ($oj.PSObject.Properties.Name -contains 'mfaChallengeToken') { $ownMfa = [string]$oj.mfaChallengeToken }
    if (-not [string]::IsNullOrWhiteSpace($ownMfa)) {
      $arcOtp = $env:SMOKE_ARCHIVE_MFA_CODE
      if ([string]::IsNullOrWhiteSpace($arcOtp)) {
        Log "owner archive: MFA challenge activo; falta SMOKE_ARCHIVE_MFA_CODE (o use SMOKE_OWNER_TOKEN_BEARER). GET /tenants quedará 403 con token pre-MFA."
        $ownerBearer = $null
      }
      else {
        Log "--- POST /auth/mfa/verify (owner archive) ---"
        $mvBody = (@{ email = $env:SMOKE_ARCHIVE_OWNER_EMAIL.Trim(); code = $arcOtp.Trim(); challengeToken = $ownMfa } | ConvertTo-Json -Compress)
        $mv = Invoke-Api -Method POST -PathRel "auth/mfa/verify" -JsonBody $mvBody
        Log "owner-archive mfa/verify status=$($mv.StatusCode)"
        if ($mv.StatusCode -ge 200 -and $mv.StatusCode -lt 300 -and $null -ne $mv.Parsed) {
          $ownerBearer = [string]$mv.Parsed.token
        }
        else {
          Log "owner-archive mfa/verify falló; trunc=$($mv.Raw.Substring(0, [Math]::Min(350, [Math]::Max(0, $mv.Raw.Length))))"
          $ownerBearer = $null
        }
      }
    }
  }
}
$tenantProfileId = $null
if (-not [string]::IsNullOrWhiteSpace($env:SMOKE_TENANT_PROFILE_ID)) { $tenantProfileId = $env:SMOKE_TENANT_PROFILE_ID.Trim() }
if (-not [string]::IsNullOrWhiteSpace($ownerBearer) -and
    [string]::IsNullOrWhiteSpace($tenantProfileId) -and
    -not [string]::IsNullOrWhiteSpace($env:SMOKE_TENANT_EMAIL)) {
  Log "--- GET /tenants (owner archive) para resolver tenantProfileId por SMOKE_TENANT_EMAIL ---"
  $ownHDiscover = @{ Authorization = "Bearer $($ownerBearer.Trim())" }
  $tenList = Invoke-Api -Method GET -PathRel "tenants" -ExtraHeaders $ownHDiscover
  Log "GET tenants status=$($tenList.StatusCode)"
  if ($tenList.StatusCode -ge 200 -and $tenList.StatusCode -lt 300 -and $null -ne $tenList.Parsed) {
    $want = $env:SMOKE_TENANT_EMAIL.Trim().ToLowerInvariant()
    foreach ($row in @($tenList.Parsed)) {
      $em = [string]$row.email
      if (-not [string]::IsNullOrWhiteSpace($em) -and $em.Trim().ToLowerInvariant() -eq $want) {
        $tenantProfileId = [string]$row.id
        Log "tenantProfileId resuelto=$tenantProfileId"
        break
      }
    }
  }
  if ([string]::IsNullOrWhiteSpace($tenantProfileId)) {
    Log "No se encontro expediente con email=$($env:SMOKE_TENANT_EMAIL) en GET /tenants; defina SMOKE_TENANT_PROFILE_ID o verifique semilla QA."
  }
}
if (-not [string]::IsNullOrWhiteSpace($ownerBearer) -and
    -not [string]::IsNullOrWhiteSpace($tenantProfileId) -and
    -not [string]::IsNullOrWhiteSpace($env:SMOKE_TENANT_EMAIL) -and
    -not [string]::IsNullOrWhiteSpace($env:SMOKE_TENANT_PASSWORD)) {
  Log "--- Escenario DELETE /tenants/{id} (archivo) + probe login tenant ---"
  $tLogin = Invoke-Api -Method POST -PathRel "auth/login" -JsonBody (@{ email = $env:SMOKE_TENANT_EMAIL; password = $env:SMOKE_TENANT_PASSWORD } | ConvertTo-Json -Compress)
  if ($tLogin.StatusCode -ge 200 -and $tLogin.StatusCode -lt 300) {
    $tTok = [string]$tLogin.Parsed.token
    $ownH = @{ Authorization = "Bearer $($ownerBearer.Trim())" }
    $del = Invoke-Api -Method DELETE -PathRel "tenants/$tenantProfileId" -ExtraHeaders $ownH
    Log "DELETE tenants status=$($del.StatusCode) body=$($del.Raw.Substring(0, [Math]::Min(300, [Math]::Max(0, $del.Raw.Length))))"
    Start-Sleep -Milliseconds 800
    $postArch = Invoke-Api -Method POST -PathRel "auth/login" -JsonBody (@{ email = $env:SMOKE_TENANT_EMAIL; password = $env:SMOKE_TENANT_PASSWORD } | ConvertTo-Json -Compress)
    Log "post-archive POST /auth/login tenant status=$($postArch.StatusCode) trunc=$($postArch.Raw.Substring(0, [Math]::Min(400, [Math]::Max(0, $postArch.Raw.Length))))"
    $archiveRan = ($del.StatusCode -ge 200 -and $del.StatusCode -lt 300) -or ($del.StatusCode -eq 204)
    $archivePostLoginOk = ($postArch.StatusCode -ge 400) -and ($postArch.Raw -match "desactivad|inactive|Cuenta|deactivated|disabled|User is disabled")
  }
  else { Log "tenant login previo a archive falló status=$($tLogin.StatusCode); skip escenario archivo" }
}
else {
  Log "SKIP escenario archivo tenant: falta owner Bearer, tenantProfileId (o email no listado en GET /tenants), o credenciales tenant"
}

# --- FASE EXTRA Etapa 0 (SMOKE_FULL_ETAPA0=1): MFA superadmin QA + staff contexts ---
if ($env:SMOKE_FULL_ETAPA0 -eq '1') {
  $defPass = $env:SMOKE_QA_SHARED_PASSWORD
  if ([string]::IsNullOrWhiteSpace($defPass)) { $defPass = "QaEtapa0-Test-2024!" }
  $totp = $env:SMOKE_QA_MFA_CODE
  if ([string]::IsNullOrWhiteSpace($totp)) {
    Log "FASE EXTRA: SKIP MFA (superadmin+staff) sin SMOKE_QA_MFA_CODE. Generar: oathtool --totp -b JBSWY3DPEHPK3PXP"
    $extraIssues += "MFA_EXTRA_SKIP"
  }
  else {
    function Do-QaMfaLogin([string]$qaEmail, [string]$qaPass, [string]$label) {
      Log "--- FASE EXTRA: login $label ---"
      $lr = Invoke-Api -Method POST -PathRel "auth/login" -JsonBody (@{ email = $qaEmail; password = $qaPass } | ConvertTo-Json -Compress)
      Log "$label login status=$($lr.StatusCode)"
      if ($lr.StatusCode -lt 200 -or $lr.StatusCode -ge 300) { return @{ ok = $false } }
      $jp = $lr.Parsed
      $ch = $null
      if ($jp.PSObject.Properties.Name -contains 'mfaChallengeToken') { $ch = [string]$jp.mfaChallengeToken }
      if ([string]::IsNullOrWhiteSpace($ch)) {
        Log "$label sin MFA challenge (token ya FULL?); contexts siguiente con Bearer login"
        return @{ ok = $true; hdr = @{ Authorization = "Bearer $([string]$jp.token)" } }
      }
      $mv = Invoke-Api -Method POST -PathRel "auth/mfa/verify" -JsonBody (@{ email = $qaEmail; code = $totp; challengeToken = $ch } | ConvertTo-Json -Compress)
      Log "$label mfa/verify status=$($mv.StatusCode)"
      if ($mv.StatusCode -lt 200 -or $mv.StatusCode -ge 300) { return @{ ok = $false } }
      $tok = [string]$mv.Parsed.token
      return @{ ok = $true; hdr = @{ Authorization = "Bearer $tok" } }
    }
    $sup = Do-QaMfaLogin "qa.etapa0.superadmin@mfa.admindi.local" $defPass "SUPERADMIN_QA"
    if (-not $sup.ok) { $extraIssues += "MFA_SUPER_FAIL" }
    else {
      $cr = Invoke-Api -Method GET -PathRel "auth/contexts" -ExtraHeaders $sup.hdr
      Log "SUPERADMIN_QA GET contexts status=$($cr.StatusCode)"
      if ($cr.StatusCode -lt 200 -or $cr.StatusCode -ge 300) { $extraIssues += "MFA_SUPER_CONTEXTS" }
    }
    $pa = Do-QaMfaLogin "qa.etapa0.staff.propadmin@test.local" $defPass "PROP_ADMIN_QA"
    if (-not $pa.ok) { $extraIssues += "MFA_PROPADMIN_FAIL" }
    else {
      $c1 = Invoke-Api -Method GET -PathRel "auth/contexts" -ExtraHeaders $pa.hdr
      Log "PROP_ADMIN GET contexts status=$($c1.StatusCode)"
      if ($c1.StatusCode -lt 200 -or $c1.StatusCode -ge 300) { $extraIssues += "PROPADMIN_CONTEXTS" }
      else {
        $alphaId = $null
        if (-not [string]::IsNullOrWhiteSpace($env:SMOKE_OWNER_ALPHA_ID)) {
          $alphaId = $env:SMOKE_OWNER_ALPHA_ID.Trim()
          Log "PROP_ADMIN Alpha contextId from SMOKE_OWNER_ALPHA_ID (override)"
        }
        else {
          $wantName = if (-not [string]::IsNullOrWhiteSpace($env:SMOKE_PROPADMIN_ALPHA_CONTEXT_NAME)) {
            $env:SMOKE_PROPADMIN_ALPHA_CONTEXT_NAME.Trim()
          } else { "QA Owner Alpha" }
          $rows = @($c1.Parsed.contexts)
          foreach ($row in $rows) {
            if ($null -eq $row) { continue }
            $nm = [string]$row.name
            if ($nm -ieq $wantName) {
              $alphaId = [string]$row.id
              Log "PROP_ADMIN Alpha contextId from GET /auth/contexts name=$nm id=$alphaId"
              break
            }
          }
          if ([string]::IsNullOrWhiteSpace($alphaId)) {
            Log "PROP_ADMIN no context matched name='$wantName' (rows=$($rows.Count))"
            foreach ($row in $rows) {
              if ($null -ne $row) { Log "  context id=$([string]$row.id) name=$([string]$row.name)" }
            }
            $extraIssues += "PROPADMIN_ALPHA_CONTEXT_NOT_FOUND"
          }
        }
        if (-not [string]::IsNullOrWhiteSpace($alphaId)) {
        $scPa = Invoke-Api -Method POST -PathRel "auth/select-context" -JsonBody (@{ contextId = $alphaId } | ConvertTo-Json -Compress) -ExtraHeaders $pa.hdr
        Log "PROP_ADMIN select-context (Owner Alpha) status=$($scPa.StatusCode)"
        if ($scPa.StatusCode -lt 200 -or $scPa.StatusCode -ge 300) {
          $extraIssues += "PROPADMIN_SELECT_ALPHA"
        }
        elseif ($null -eq $scPa.Parsed -or $null -eq $scPa.Parsed.token) {
          $extraIssues += "PROPADMIN_SELECT_ALPHA_NO_TOKEN"
        }
        else {
          $paFull = @{ Authorization = "Bearer $([string]$scPa.Parsed.token)" }
          $gtPa = Invoke-Api -Method GET -PathRel "tenants" -ExtraHeaders $paFull
          Log "PROP_ADMIN GET tenants (Alpha, tpl-property-admin-*) status=$($gtPa.StatusCode)"
          if ($gtPa.StatusCode -ne 200) { $extraIssues += "PROPADMIN_TENANTS" }
          $dummyProfile = "11111111-1111-4111-8111-111111111111"
          $delProbe = Invoke-Api -Method DELETE -PathRel "tenants/$dummyProfile" -ExtraHeaders $paFull
          $delTrunc = if ($delProbe.Raw.Length -gt 0) { $delProbe.Raw.Substring(0, [Math]::Min(220, $delProbe.Raw.Length)) } else { "" }
          Log "PROP_ADMIN DELETE tenants dummy (permiso PROPERTY_ARCHIVE_TENANT) status=$($delProbe.StatusCode) trunc=$delTrunc"
          if ($delProbe.StatusCode -eq 403) { $extraIssues += "PROPADMIN_ARCHIVE_PERM" }
          elseif ($delProbe.StatusCode -ne 400) { $extraIssues += "PROPADMIN_ARCHIVE_UNEXPECTED" }
        }
        }
      }
    }
    $ac = Do-QaMfaLogin "qa.etapa0.staff.accountant@test.local" $defPass "ACCOUNTANT_QA"
    if (-not $ac.ok) { $extraIssues += "MFA_ACCOUNTANT_FAIL" }
    else {
      $c2 = Invoke-Api -Method GET -PathRel "auth/contexts" -ExtraHeaders $ac.hdr
      Log "ACCOUNTANT GET contexts status=$($c2.StatusCode)"
      if ($c2.StatusCode -lt 200 -or $c2.StatusCode -ge 300) { $extraIssues += "ACCOUNTANT_CONTEXTS" }
    }
  }
}

$log | Set-Content -Encoding utf8 (Join-Path $OutDir "SMOKE_HTTP.log")

$reuseBad = ($reuse.StatusCode -eq 400) -and ($reuse.Raw -match "reuse|revoked|Invalid|expired|Token")
if (-not $reuseBad) { $reuseBad = ($reuse.StatusCode -ge 400) }
$logoutOk = ($lo.StatusCode -eq 204)
$postLogoutDenied = ($probeTok.StatusCode -eq 403) -or ($probeTok.StatusCode -eq 401)
$issues = @()
if (-not $redisOk) { $issues += "REDIS" }
if (-not $reuseBad) { $issues += "REUSE" }
if (-not $logoutOk) { $issues += "LOGOUT" }
if ($logoutOk -and -not $postLogoutDenied) { $issues += "POST_LOGOUT" }
if ($archiveRan -and -not $archivePostLoginOk) { $issues += "ARCHIVE_PROBE" }
foreach ($e in $extraIssues) { $issues += $e }
$verdict = if ($issues.Count -eq 0) { "SMOKE_RESULT=PASSED_AUTH" } else { "SMOKE_RESULT=PARTIAL_$($issues -join '_')" }
if (-not $encKeySet) { $verdict += ";PROD_BLOCKER_NO_ENCRYPTION_KEY" }
$verdict | Set-Content -Encoding utf8 (Join-Path $OutDir "SMOKE_RESULT.txt")

exit 0
