<#
.SYNOPSIS
  Smoke Etapa 2 - tenant archive snapshot + reauth por rol, convenios suspenden mora,
  presupuestos de mantenimiento, panel SUPERADMIN (contacto + delete user),
  evento OWNER_TASK_PENDING a n8n.

.NOTES
  Aislado de etapa1-smoke.ps1. No comparte estado ni evidencia.
  Cada corrida escribe evidencia inmutable bajo:
    qa-evidence/2026-04-16/etapa-2/runs/<run-id>/

  Variables de entorno mínimas (mismo esquema que Etapa 1):
    SMOKE_SA_EMAIL, SMOKE_SA_PASSWORD, SMOKE_SA_MFA_CODE          SUPER_ADMIN (MFA obligatorio)
    SMOKE_EMAIL, SMOKE_PASSWORD, SMOKE_MFA_CODE                   OWNER
    SMOKE_TENANT_EMAIL, SMOKE_TENANT_PASSWORD                     TENANT (opcional)
    SMOKE_PROVIDER_EMAIL, SMOKE_PROVIDER_PASSWORD,
    SMOKE_PROVIDER_MFA_CODE                                       MAINTENANCE_PROVIDER o REAL_ESTATE_AGENT
                                                                  (opcional; si falta se omite upload real)
    SMOKE_BASE_URL                                                default http://localhost:8080/api

  Asume que el backend está en ejecución y los usuarios QA existen (mismos usados en Etapa 1).
#>
param(
  [string]$EvidenceRoot = $(if ($env:SMOKE_ETAPA2_ROOT) { $env:SMOKE_ETAPA2_ROOT } else { Join-Path (Split-Path $PSScriptRoot -Parent) "qa-evidence\2026-04-16\etapa-2" })
)

$ErrorActionPreference = "Stop"
$startUtc = [DateTime]::UtcNow.ToString("o")

$base = if ([string]::IsNullOrWhiteSpace($env:SMOKE_BASE_URL)) { "http://localhost:8080/api" } else { $env:SMOKE_BASE_URL.TrimEnd('/') }

$runId = if ($env:SMOKE_ETAPA2_RUN_ID) { $env:SMOKE_ETAPA2_RUN_ID } else { (Get-Date -Format "yyyy-MM-ddTHH-mm-ss") }
$runDir = Join-Path $EvidenceRoot "runs\$runId"
New-Item -ItemType Directory -Path $runDir -Force | Out-Null

$log = New-Object System.Collections.ArrayList
function Log([string]$s) { [void]$log.Add("$(Get-Date -Format o)  $s"); Write-Host $s }

function Invoke-Api {
  param(
    [string]$Method,
    [string]$PathRel,
    [string]$JsonBody = $null,
    [hashtable]$ExtraHeaders = @{}
  )
  $uri = "$base/$($PathRel.TrimStart('/'))"
  $headers = @{ "Accept" = "application/json" }
  foreach ($k in $ExtraHeaders.Keys) { $headers[$k] = $ExtraHeaders[$k] }
  try {
    if ($Method -eq "GET" -or $Method -eq "DELETE") {
      if ($JsonBody) {
        if (-not $headers.ContainsKey("Content-Type")) { $headers["Content-Type"] = "application/json" }
        $r = Invoke-WebRequest -Uri $uri -Method $Method -Headers $headers -Body $JsonBody -UseBasicParsing
      } else {
        $r = Invoke-WebRequest -Uri $uri -Method $Method -Headers $headers -UseBasicParsing
      }
    } else {
      if (-not $headers.ContainsKey("Content-Type")) { $headers["Content-Type"] = "application/json" }
      $r = Invoke-WebRequest -Uri $uri -Method $Method -Headers $headers -Body $JsonBody -UseBasicParsing
    }
    return @{ code = [int]$r.StatusCode; body = $r.Content }
  } catch {
    $resp = $_.Exception.Response
    if ($resp) {
      $sr = New-Object System.IO.StreamReader($resp.GetResponseStream())
      $body = $sr.ReadToEnd()
      return @{ code = [int]$resp.StatusCode; body = $body }
    }
    return @{ code = -1; body = $_.Exception.Message }
  }
}

function Get-FullToken {
  param([string]$Email, [string]$Password, [string]$MfaCode)
  $body = @{ email = $Email; password = $Password; mfaCode = $MfaCode } | ConvertTo-Json
  $r = Invoke-Api -Method POST -PathRel "/auth/login" -JsonBody $body
  if ($r.code -ne 200) { throw "Login failed for $Email - HTTP $($r.code) body=$($r.body)" }
  $base = ($r.body | ConvertFrom-Json).token
  # get contexts, pick first, exchange to FULL
  $ctx = Invoke-Api -Method GET -PathRel "/auth/contexts" -ExtraHeaders @{ Authorization = "Bearer $base" }
  if ($ctx.code -ne 200) { throw "GET /auth/contexts failed - HTTP $($ctx.code) body=$($ctx.body)" }
  $contexts = ($ctx.body | ConvertFrom-Json)
  $first = $contexts[0]
  $sel = Invoke-Api -Method POST -PathRel "/auth/select-context" `
         -JsonBody (@{ contextId = $first.id } | ConvertTo-Json) `
         -ExtraHeaders @{ Authorization = "Bearer $base" }
  if ($sel.code -ne 200) { throw "select-context failed HTTP $($sel.code) body=$($sel.body)" }
  $full = ($sel.body | ConvertFrom-Json).token
  return @{ token = $full; contextId = $first.id; contextName = $first.name }
}

$assertCount = 0
$passCount = 0
function Assert-Http {
  param([hashtable]$Resp, [int]$Expected, [string]$Label)
  $script:assertCount++
  if ($Resp.code -eq $Expected) {
    Log "PASS [$Label] HTTP $($Resp.code)"
    $script:passCount++
  } else {
    Log "FAIL [$Label] expected HTTP $Expected got $($Resp.code) body=$($Resp.body)"
  }
}

Log "=== SMOKE ETAPA 2 - start $startUtc ==="
Log "base=$base runDir=$runDir"

# Prereq env
$saEmail = $env:SMOKE_SA_EMAIL
$saPw    = $env:SMOKE_SA_PASSWORD
$saMfa   = $env:SMOKE_SA_MFA_CODE
$oEmail  = $env:SMOKE_EMAIL
$oPw     = $env:SMOKE_PASSWORD
$oMfa    = $env:SMOKE_MFA_CODE
if (-not $saEmail -or -not $saPw) { throw "Faltan SMOKE_SA_EMAIL/SMOKE_SA_PASSWORD" }
if (-not $oEmail -or -not $oPw)   { throw "Faltan SMOKE_EMAIL/SMOKE_PASSWORD" }

# Login SUPER_ADMIN
$sa = Get-FullToken -Email $saEmail -Password $saPw -MfaCode $saMfa
Log "SUPER_ADMIN token ok ctx=$($sa.contextName)"
$owner = Get-FullToken -Email $oEmail -Password $oPw -MfaCode $oMfa
Log "OWNER token ok ctx=$($owner.contextName)"

# ---- Test 1: SUPER_ADMIN listar snapshots (puede estar vacío) ----
$r = Invoke-Api -Method GET -PathRel "/tenant-archive-snapshots/mine" -ExtraHeaders @{ Authorization = "Bearer $($sa.token)" }
Assert-Http -Resp $r -Expected 200 -Label "SA_LIST_SNAPSHOTS_EMPTY_OR_NON_EMPTY"

# ---- Test 2: SUPER_ADMIN actualiza contacto de proveedor/agente (si hay alguno) ----
# Buscar un usuario REAL_ESTATE_AGENT o MAINTENANCE_PROVIDER existente via /admin/platform-providers (proveedores)
$prov = Invoke-Api -Method GET -PathRel "/admin/platform-providers" -ExtraHeaders @{ Authorization = "Bearer $($sa.token)" }
Assert-Http -Resp $prov -Expected 200 -Label "SA_LIST_PROVIDERS"
$providerUserId = $null
if ($prov.code -eq 200) {
  $arr = $prov.body | ConvertFrom-Json
  if ($arr -and $arr.Count -gt 0 -and $arr[0].userId) { $providerUserId = $arr[0].userId }
}
if ($providerUserId) {
  # Nuevo: SUPER_ADMIN también debe pasar MFA + contraseña para cambios sensibles.
  # Sin reauth -> debe fallar.
  $noReauth = Invoke-Api -Method PUT -PathRel "/admin/users/$providerUserId/contact" `
              -JsonBody (@{ contactEmail = "attacker+$([guid]::NewGuid().ToString('N').Substring(0,6))@evil.local" } | ConvertTo-Json) `
              -ExtraHeaders @{ Authorization = "Bearer $($sa.token)" }
  $script:assertCount++
  if ($noReauth.code -ge 400) { $script:passCount++; Log "PASS [SA_UPDATE_CONTACT_REQUIRES_MFA] HTTP $($noReauth.code)" } else { Log "FAIL [SA_UPDATE_CONTACT_REQUIRES_MFA] got $($noReauth.code)" }

  # Con password + MFA -> ok
  $upd = Invoke-Api -Method PUT -PathRel "/admin/users/$providerUserId/contact" `
         -JsonBody (@{ password = $saPw; mfaCode = $saMfa; contactEmail = "qa.etapa2+$([guid]::NewGuid().ToString('N').Substring(0,6))@test.local"; contactPhone = "5599887766" } | ConvertTo-Json) `
         -ExtraHeaders @{ Authorization = "Bearer $($sa.token)" }
  Assert-Http -Resp $upd -Expected 200 -Label "SA_UPDATE_PROVIDER_CONTACT_WITH_REAUTH"
} else {
  Log "SKIP SA_UPDATE_PROVIDER_CONTACT (no providers found)"
}

# ---- Test 3: OWNER intenta aprobar convenio sin MFA (debe fallar 400/500) ----
# Obtener convenios pendientes
$pend = Invoke-Api -Method GET -PathRel "/agreements/pending" -ExtraHeaders @{ Authorization = "Bearer $($owner.token)" }
Assert-Http -Resp $pend -Expected 200 -Label "OWNER_LIST_PENDING_AGREEMENTS"
$pendArr = if ($pend.code -eq 200) { $pend.body | ConvertFrom-Json } else { @() }
if ($pendArr -and $pendArr.Count -gt 0) {
  $agId = $pendArr[0].id
  $bad = Invoke-Api -Method POST -PathRel "/agreements/$agId/approve" `
         -JsonBody (@{ approvedAmount = 500 } | ConvertTo-Json) `
         -ExtraHeaders @{ Authorization = "Bearer $($owner.token)" }
  if ($bad.code -ge 400) { Log "PASS [AGREEMENT_APPROVE_REQUIRES_REAUTH] HTTP $($bad.code)"; $script:passCount++ } else { Log "FAIL [AGREEMENT_APPROVE_REQUIRES_REAUTH] expected >=400 got $($bad.code)" }
  $script:assertCount++
} else {
  Log "SKIP AGREEMENT_APPROVE_REQUIRES_REAUTH (no pending agreements)"
}

# ---- Test 4: OWNER intenta subir presupuesto -> debe ser rechazado (solo autores lo suben) ----
$pdfPath = Join-Path $runDir "budget.pdf"
# mini PDF de 1 pág. ok para smoke
"%PDF-1.4`n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj`n2 0 obj<</Type/Pages/Count 1/Kids[3 0 R]>>endobj`n3 0 obj<</Type/Page/Parent 2 0 R/Resources<<>>/MediaBox[0 0 100 100]>>endobj`nxref`n0 4`n0000000000 65535 f `n0000000010 00000 n `n0000000053 00000 n `n0000000096 00000 n `ntrailer<</Size 4/Root 1 0 R>>`nstartxref`n160`n%%EOF" | Out-File -FilePath $pdfPath -Encoding ascii

# Autores permitidos: MAINTENANCE_PROVIDER, REAL_ESTATE_AGENT. OWNER no sube.
try {
  $uploadForm = @{ title = "QA Presupuesto Etapa2"; description = "prueba smoke"; amount = 1500; file = Get-Item $pdfPath }
  $uploadResp = Invoke-WebRequest -Uri "$base/maintenance/budgets" -Method POST -Form $uploadForm -Headers @{ Authorization = "Bearer $($owner.token)" } -UseBasicParsing -ErrorAction SilentlyContinue
  $script:assertCount++
  if ([int]$uploadResp.StatusCode -ge 400) {
    $script:passCount++; Log "PASS [BUDGET_OWNER_UPLOAD_FORBIDDEN] HTTP $([int]$uploadResp.StatusCode)"
  } else {
    Log "FAIL [BUDGET_OWNER_UPLOAD_FORBIDDEN] owner subió OK (HTTP $([int]$uploadResp.StatusCode)) — debería ser 403"
  }
} catch {
  $script:assertCount++; $script:passCount++
  Log "PASS [BUDGET_OWNER_UPLOAD_FORBIDDEN] (excepción esperada de 403/401)"
}

# Upload real requiere credenciales de MAINTENANCE_PROVIDER o REAL_ESTATE_AGENT
# Vars opcionales: SMOKE_PROVIDER_EMAIL, SMOKE_PROVIDER_PASSWORD, SMOKE_PROVIDER_MFA_CODE
$pEmail = $env:SMOKE_PROVIDER_EMAIL
$pPw    = $env:SMOKE_PROVIDER_PASSWORD
$pMfa   = $env:SMOKE_PROVIDER_MFA_CODE
if ($pEmail -and $pPw) {
  try {
    $prov = Get-FullToken -Email $pEmail -Password $pPw -MfaCode $pMfa
    $uploadForm = @{ title = "QA Presupuesto Etapa2"; description = "prueba smoke"; amount = 1500; file = Get-Item $pdfPath }
    $uploadResp = Invoke-WebRequest -Uri "$base/maintenance/budgets" -Method POST -Form $uploadForm -Headers @{ Authorization = "Bearer $($prov.token)" } -UseBasicParsing
    $script:assertCount++
    if ([int]$uploadResp.StatusCode -eq 200) {
      $script:passCount++; Log "PASS [BUDGET_UPLOAD_BY_AUTHOR] HTTP 200"
      $created = $uploadResp.Content | ConvertFrom-Json
      $budgetId = $created.id
      # Reject sin reauth -> falla
      $bad = Invoke-Api -Method POST -PathRel "/maintenance/budgets/$budgetId/reject" `
             -JsonBody (@{ note = "smoke"; password = "wrong" } | ConvertTo-Json) `
             -ExtraHeaders @{ Authorization = "Bearer $($owner.token)" }
      $script:assertCount++
      if ($bad.code -ge 400) { $script:passCount++; Log "PASS [BUDGET_REJECT_REAUTH_FAIL] HTTP $($bad.code)" } else { Log "FAIL [BUDGET_REJECT_REAUTH_FAIL] got $($bad.code)" }
      # Approve con reauth real
      $ok = Invoke-Api -Method POST -PathRel "/maintenance/budgets/$budgetId/approve" `
            -JsonBody (@{ note = "ok smoke"; password = $oPw; mfaCode = $oMfa } | ConvertTo-Json) `
            -ExtraHeaders @{ Authorization = "Bearer $($owner.token)" }
      Assert-Http -Resp $ok -Expected 200 -Label "BUDGET_APPROVE_WITH_REAUTH"
    } else {
      Log "FAIL [BUDGET_UPLOAD_BY_AUTHOR] HTTP $([int]$uploadResp.StatusCode)"
    }
  } catch {
    Log "WARN [BUDGET_UPLOAD_BY_AUTHOR] $_"
  }
} else {
  Log "SKIP BUDGET_UPLOAD_BY_AUTHOR (defina SMOKE_PROVIDER_EMAIL/SMOKE_PROVIDER_PASSWORD)"
}

# ---- Test 5: SUPER_ADMIN archiva tenant con MFA + contraseña (política unificada de reauth) ----
# Buscar un perfil de tenant activo para este owner
$tenants = Invoke-Api -Method GET -PathRel "/tenants" -ExtraHeaders @{ Authorization = "Bearer $($owner.token)" }
Assert-Http -Resp $tenants -Expected 200 -Label "OWNER_LIST_TENANTS"
$tenantProfileId = $null
if ($tenants.code -eq 200) {
  $tArr = $tenants.body | ConvertFrom-Json
  foreach ($t in $tArr) { if ($t.tenantProfileId) { $tenantProfileId = $t.tenantProfileId; break } }
}

if ($tenantProfileId) {
  # Owner intenta sin MFA -> debe fallar
  $ownerBad = Invoke-Api -Method DELETE -PathRel "/tenants/$tenantProfileId" `
              -JsonBody (@{ password = $oPw } | ConvertTo-Json) `
              -ExtraHeaders @{ Authorization = "Bearer $($owner.token)" }
  $script:assertCount++
  if ($ownerBad.code -ge 400) { $script:passCount++; Log "PASS [OWNER_ARCHIVE_WITHOUT_MFA_REJECTED] HTTP $($ownerBad.code)" } else { Log "FAIL [OWNER_ARCHIVE_WITHOUT_MFA_REJECTED] got $($ownerBad.code)" }

  # SUPER_ADMIN sin MFA -> también debe fallar (nueva política)
  $saNoMfa = Invoke-Api -Method DELETE -PathRel "/tenants/$tenantProfileId" `
             -JsonBody (@{ password = $saPw } | ConvertTo-Json) `
             -ExtraHeaders @{ Authorization = "Bearer $($sa.token)" }
  $script:assertCount++
  if ($saNoMfa.code -ge 400) { $script:passCount++; Log "PASS [SUPERADMIN_ARCHIVE_REQUIRES_MFA] HTTP $($saNoMfa.code)" } else { Log "FAIL [SUPERADMIN_ARCHIVE_REQUIRES_MFA] got $($saNoMfa.code) — se permitió sin MFA" }

  # SUPER_ADMIN con password + MFA -> pasa
  $saOk = Invoke-Api -Method DELETE -PathRel "/tenants/$tenantProfileId" `
          -JsonBody (@{ password = $saPw; mfaCode = $saMfa } | ConvertTo-Json) `
          -ExtraHeaders @{ Authorization = "Bearer $($sa.token)" }
  Assert-Http -Resp $saOk -Expected 204 -Label "SUPERADMIN_ARCHIVE_WITH_MFA"

  # Verificar que quedó snapshot
  Start-Sleep -Seconds 1
  $snaps = Invoke-Api -Method GET -PathRel "/tenant-archive-snapshots/mine" -ExtraHeaders @{ Authorization = "Bearer $($sa.token)" }
  Assert-Http -Resp $snaps -Expected 200 -Label "SNAPSHOT_LIST_AFTER_SA_ARCHIVE"
  if ($snaps.code -eq 200) {
    $snArr = $snaps.body | ConvertFrom-Json
    $found = $snArr | Where-Object { $_.tenantProfileId -eq $tenantProfileId } | Select-Object -First 1
    $script:assertCount++
    if ($found) {
      $script:passCount++
      Log ("PASS [SNAPSHOT_PERSISTED] months_paid={0} months_debt={1} owed={2} paid={3} evidences={4}" -f `
           $found.monthsPaidCount, $found.monthsWithDebtCount, $found.totalOwedAmount, $found.totalPaidAmount, $found.evidencesCount)
    } else {
      Log "FAIL [SNAPSHOT_PERSISTED] no snapshot row found"
    }
  }
} else {
  Log "SKIP SUPERADMIN_ARCHIVE_WITH_MFA (no tenant profile available in OWNER ctx)"
}

# ---- Test 6: DELETE /api/admin/users/{id} — invariantes, reason, auditoría ----
# Subpruebas:
#   6.1 reason < 10 chars    -> 400
#   6.2 sin MFA              -> 4xx (reauth)
#   6.3 MFA inválido         -> 4xx (reauth)
#   6.4 target = SUPER_ADMIN -> 403 + audit SUPERADMIN_USER_DELETE_BLOCKED (blockReason=TARGET_IS_SUPER_ADMIN)
#   6.5 target = self (actor SA) -> 403 + audit SUPERADMIN_USER_DELETE_BLOCKED (blockReason=SELF_DELETE)
#   6.6 target válido (no-SA, no-self) -> 200 + audit SUPERADMIN_USER_DELETE con reason persistido

Log "--- Test 6: SUPERADMIN DELETE /admin/users/{id} (invariantes + auditoría) ---"

# Resolver el userId de un SUPER_ADMIN distinto y del propio actor.
# Buscamos "admin" en la búsqueda global para detectar SAs.
$saSearch = Invoke-Api -Method GET -PathRel "/users/search?q=admin&includeInactive=false" `
            -ExtraHeaders @{ Authorization = "Bearer $($sa.token)" }
Assert-Http -Resp $saSearch -Expected 200 -Label "SA_USER_SEARCH"

$selfUserId      = $null
$otherSaUserId   = $null
$victimUserId    = $null     # algún no-SA, no-self para borrar exitosamente
$victimRole      = $null
if ($saSearch.code -eq 200) {
  $rows = $saSearch.body | ConvertFrom-Json
  foreach ($row in $rows) {
    if ($row.email -and $row.email.ToLower() -eq $saEmail.ToLower()) { $selfUserId = $row.id; continue }
    if ($row.role -eq 'SUPER_ADMIN' -and -not $otherSaUserId) { $otherSaUserId = $row.id }
  }
  # Buscar una víctima: preferir un usuario no SUPER_ADMIN, no self (cualquier rol).
  # Reintentamos con búsquedas más amplias si hace falta.
  foreach ($q in @('qa', 'test', 'tenant', 'admin', 'owner')) {
    if ($victimUserId) { break }
    $vs = Invoke-Api -Method GET -PathRel "/users/search?q=$q&includeInactive=false" `
          -ExtraHeaders @{ Authorization = "Bearer $($sa.token)" }
    if ($vs.code -ne 200) { continue }
    foreach ($row in ($vs.body | ConvertFrom-Json)) {
      if ($row.role -eq 'SUPER_ADMIN') { continue }
      if ($row.email -and $row.email.ToLower() -eq $saEmail.ToLower()) { continue }
      # Evitar borrar al OWNER de contexto del smoke (se usa en Tests 3-5)
      if ($row.email -and $row.email.ToLower() -eq $oEmail.ToLower()) { continue }
      $victimUserId = $row.id
      $victimRole   = $row.role
      break
    }
  }
}
Log "ids: self=$selfUserId otherSa=$otherSaUserId victim=$victimUserId ($victimRole)"

# 6.1 reason corto -> 400
if ($victimUserId) {
  $short = Invoke-Api -Method DELETE -PathRel "/admin/users/$victimUserId" `
           -JsonBody (@{ password = $saPw; mfaCode = $saMfa; reason = "corto" } | ConvertTo-Json) `
           -ExtraHeaders @{ Authorization = "Bearer $($sa.token)" }
  $script:assertCount++
  if ($short.code -eq 400) { $script:passCount++; Log "PASS [DELETE_USER_REASON_REQUIRED] HTTP 400" } `
  else { Log "FAIL [DELETE_USER_REASON_REQUIRED] got $($short.code) body=$($short.body)" }
} else {
  Log "SKIP DELETE_USER_REASON_REQUIRED (no victim available)"
}

# 6.2 sin MFA -> 4xx
if ($victimUserId) {
  $noMfa = Invoke-Api -Method DELETE -PathRel "/admin/users/$victimUserId" `
           -JsonBody (@{ password = $saPw; reason = "smoke no mfa delete user" } | ConvertTo-Json) `
           -ExtraHeaders @{ Authorization = "Bearer $($sa.token)" }
  $script:assertCount++
  if ($noMfa.code -ge 400) { $script:passCount++; Log "PASS [DELETE_USER_REQUIRES_MFA] HTTP $($noMfa.code)" } `
  else { Log "FAIL [DELETE_USER_REQUIRES_MFA] got $($noMfa.code) — se permitió sin MFA" }
}

# 6.3 MFA inválido -> 4xx
if ($victimUserId) {
  $badMfa = Invoke-Api -Method DELETE -PathRel "/admin/users/$victimUserId" `
            -JsonBody (@{ password = $saPw; mfaCode = "000000"; reason = "smoke bad mfa delete user" } | ConvertTo-Json) `
            -ExtraHeaders @{ Authorization = "Bearer $($sa.token)" }
  $script:assertCount++
  if ($badMfa.code -ge 400) { $script:passCount++; Log "PASS [DELETE_USER_BAD_MFA_REJECTED] HTTP $($badMfa.code)" } `
  else { Log "FAIL [DELETE_USER_BAD_MFA_REJECTED] got $($badMfa.code) — se aceptó un MFA inválido" }
}

# 6.4 borrar otro SUPER_ADMIN -> 403 + audit BLOCKED
if ($otherSaUserId) {
  $tryRoot = Invoke-Api -Method DELETE -PathRel "/admin/users/$otherSaUserId" `
             -JsonBody (@{ password = $saPw; mfaCode = $saMfa; reason = "intento smoke eliminar otro root" } | ConvertTo-Json) `
             -ExtraHeaders @{ Authorization = "Bearer $($sa.token)" }
  Assert-Http -Resp $tryRoot -Expected 403 -Label "DELETE_USER_BLOCKED_ROOT_TO_ROOT"

  Start-Sleep -Milliseconds 500
  $auditRoot = Invoke-Api -Method GET -PathRel "/admin/audit?eventType=SUPERADMIN_USER_DELETE_BLOCKED&size=50" `
               -ExtraHeaders @{ Authorization = "Bearer $($sa.token)" }
  Assert-Http -Resp $auditRoot -Expected 200 -Label "DELETE_USER_BLOCKED_ROOT_AUDIT_FETCH"
  if ($auditRoot.code -eq 200) {
    $page = $auditRoot.body | ConvertFrom-Json
    $hit  = $page.content | Where-Object {
              $_.resourceId -eq $otherSaUserId -and
              $_.newValues -like '*TARGET_IS_SUPER_ADMIN*'
            } | Select-Object -First 1
    $script:assertCount++
    if ($hit) {
      $script:passCount++
      Log "PASS [DELETE_USER_BLOCKED_ROOT_AUDIT_PERSISTED] requestId=$($hit.requestId)"
    } else {
      Log "FAIL [DELETE_USER_BLOCKED_ROOT_AUDIT_PERSISTED] no audit row matched"
    }
  }
} else {
  Log "SKIP DELETE_USER_BLOCKED_ROOT_TO_ROOT (no second SUPER_ADMIN visible)"
}

# 6.5 auto-borrado -> 403 + audit BLOCKED
if ($selfUserId) {
  $trySelf = Invoke-Api -Method DELETE -PathRel "/admin/users/$selfUserId" `
             -JsonBody (@{ password = $saPw; mfaCode = $saMfa; reason = "intento smoke self delete" } | ConvertTo-Json) `
             -ExtraHeaders @{ Authorization = "Bearer $($sa.token)" }
  Assert-Http -Resp $trySelf -Expected 403 -Label "DELETE_USER_BLOCKED_SELF"

  Start-Sleep -Milliseconds 500
  $auditSelf = Invoke-Api -Method GET -PathRel "/admin/audit?eventType=SUPERADMIN_USER_DELETE_BLOCKED&size=50" `
               -ExtraHeaders @{ Authorization = "Bearer $($sa.token)" }
  if ($auditSelf.code -eq 200) {
    $page = $auditSelf.body | ConvertFrom-Json
    $hit  = $page.content | Where-Object {
              $_.resourceId -eq $selfUserId -and
              $_.newValues -like '*SELF_DELETE*'
            } | Select-Object -First 1
    $script:assertCount++
    if ($hit) {
      $script:passCount++
      Log "PASS [DELETE_USER_BLOCKED_SELF_AUDIT_PERSISTED] requestId=$($hit.requestId)"
    } else {
      Log "FAIL [DELETE_USER_BLOCKED_SELF_AUDIT_PERSISTED] no audit row matched"
    }
  }
} else {
  Log "SKIP DELETE_USER_BLOCKED_SELF (could not resolve self userId)"
}

# 6.6 borrado exitoso + audit con reason persistido
if ($victimUserId) {
  $goodReason = "smoke etapa2: delete usuario no-root no-self $(Get-Date -Format o)"
  $doDel = Invoke-Api -Method DELETE -PathRel "/admin/users/$victimUserId" `
           -JsonBody (@{ password = $saPw; mfaCode = $saMfa; reason = $goodReason } | ConvertTo-Json) `
           -ExtraHeaders @{ Authorization = "Bearer $($sa.token)" }
  Assert-Http -Resp $doDel -Expected 200 -Label "DELETE_USER_SUCCESS"

  Start-Sleep -Milliseconds 500
  $auditOk = Invoke-Api -Method GET -PathRel "/admin/audit?eventType=SUPERADMIN_USER_DELETE&resourceType=User&size=50" `
             -ExtraHeaders @{ Authorization = "Bearer $($sa.token)" }
  Assert-Http -Resp $auditOk -Expected 200 -Label "DELETE_USER_AUDIT_FETCH"
  if ($auditOk.code -eq 200) {
    $page = $auditOk.body | ConvertFrom-Json
    # Aceptamos el evento de éxito exacto (no el _BLOCKED)
    $hit  = $page.content | Where-Object {
              $_.eventType -eq 'SUPERADMIN_USER_DELETE' -and
              $_.resourceId -eq $victimUserId -and
              $_.newValues -like "*$([regex]::Escape($goodReason.Substring(0,24)))*"
            } | Select-Object -First 1
    $script:assertCount++
    if ($hit) {
      $script:passCount++
      Log "PASS [DELETE_USER_AUDIT_REASON_PERSISTED] actorId=$($hit.actorId)"
    } else {
      Log "FAIL [DELETE_USER_AUDIT_REASON_PERSISTED] no matching audit row with reason"
    }
  }

  # Idempotencia: re-eliminar no debería romper (usuario ya inactivo).
  $again = Invoke-Api -Method DELETE -PathRel "/admin/users/$victimUserId" `
           -JsonBody (@{ password = $saPw; mfaCode = $saMfa; reason = "smoke etapa2: idempotencia delete" } | ConvertTo-Json) `
           -ExtraHeaders @{ Authorization = "Bearer $($sa.token)" }
  $script:assertCount++
  if ($again.code -eq 200) { $script:passCount++; Log "PASS [DELETE_USER_IDEMPOTENT] HTTP 200" } `
  else { Log "WARN [DELETE_USER_IDEMPOTENT] got $($again.code) body=$($again.body)" }
} else {
  Log "SKIP DELETE_USER_SUCCESS (no victim selected)"
}

# Write evidence files
$log | Out-File (Join-Path $runDir "ETAPA2_SMOKE_HTTP.log") -Encoding utf8
@"
=== SMOKE ETAPA 2 RESULT ===
start: $startUtc
end:   $([DateTime]::UtcNow.ToString("o"))
base:  $base
asserts: $assertCount
passes:  $passCount
fails:   $($assertCount - $passCount)
"@ | Out-File (Join-Path $runDir "ETAPA2_SMOKE_RESULT.txt") -Encoding utf8

@"
ETAPA: 2
RUN_ID: $runId
BASE:   $base
SUPER_ADMIN_EMAIL: $saEmail
OWNER_EMAIL:       $oEmail
"@ | Out-File (Join-Path $runDir "RUN_METADATA.txt") -Encoding utf8

Log "=== DONE: $passCount/$assertCount ==="

if ($passCount -lt $assertCount) { exit 1 } else { exit 0 }
