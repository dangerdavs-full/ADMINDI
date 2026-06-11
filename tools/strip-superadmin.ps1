# ============================================================================
# Fase 1b — Remueve SUPER_ADMIN de @PreAuthorize en controllers Categoría B
# Ejecución única. Verifica con -WhatIf primero si se desea.
# ============================================================================

$ErrorActionPreference = 'Stop'
$base = Join-Path $PSScriptRoot ".." | Resolve-Path
$ctrlDir = Join-Path $base "backend\src\main\java\com\admindi\backend\controller"

# Archivos Categoría B — SUPER_ADMIN debe salir de @PreAuthorize
$categoryB = @(
  'PropertyController.java',
  'OwnerWorkflowController.java',
  'TenantController.java',
  'ApprovalRequestController.java',
  'TenantArchiveSnapshotController.java',
  'AccountingReconciliationController.java',
  'ActionTaskController.java',
  'LedgerController.java',
  'MaintenanceController.java',
  'PaymentAgreementController.java',
  'MaintenanceBudgetController.java',
  'CommercialActivityController.java',
  'OwnerAgentPriorityController.java',
  'VacancyController.java',
  'StaffController.java',
  'PropertyFileController.java',
  'LeaseController.java',
  'OwnerAccountingController.java',
  'MetricsController.java',
  'ReportController.java',
  'MercadoPagoController.java',
  'UnitController.java'
)

# Reemplazos ordenados (más específicos primero para no romper patrones cortos).
# Sólo tocan tokens dentro de comillas simples de @PreAuthorize.
$replacements = @(
  # 6-role: SA + 5
  @{ From = "'SUPER_ADMIN',\s*'OWNER',\s*'PROPERTY_ADMIN',\s*'ACCOUNTANT',\s*'MAINTENANCE_PROVIDER',\s*'REAL_ESTATE_AGENT'"; To = "'OWNER','PROPERTY_ADMIN','ACCOUNTANT','MAINTENANCE_PROVIDER','REAL_ESTATE_AGENT'" },
  # 5-role: SA + 4 incluyendo agent
  @{ From = "'SUPER_ADMIN',\s*'OWNER',\s*'PROPERTY_ADMIN',\s*'ACCOUNTANT',\s*'REAL_ESTATE_AGENT'"; To = "'OWNER','PROPERTY_ADMIN','ACCOUNTANT','REAL_ESTATE_AGENT'" },
  # 4-role: SA + 3 con REAL_ESTATE_AGENT
  @{ From = "'SUPER_ADMIN',\s*'OWNER',\s*'PROPERTY_ADMIN',\s*'REAL_ESTATE_AGENT'"; To = "'OWNER','PROPERTY_ADMIN','REAL_ESTATE_AGENT'" },
  @{ From = "'SUPER_ADMIN',\s*'REAL_ESTATE_AGENT',\s*'PROPERTY_ADMIN',\s*'OWNER'";  To = "'REAL_ESTATE_AGENT','PROPERTY_ADMIN','OWNER'" },
  @{ From = "'SUPER_ADMIN',\s*'REAL_ESTATE_AGENT',\s*'PROPERTY_ADMIN'";            To = "'REAL_ESTATE_AGENT','PROPERTY_ADMIN'" },
  # 4-role variants
  @{ From = "'SUPER_ADMIN',\s*'OWNER',\s*'PROPERTY_ADMIN',\s*'ACCOUNTANT'"; To = "'OWNER','PROPERTY_ADMIN','ACCOUNTANT'" },
  # 3-role
  @{ From = "'SUPER_ADMIN',\s*'OWNER',\s*'PROPERTY_ADMIN'"; To = "'OWNER','PROPERTY_ADMIN'" },
  @{ From = "'SUPER_ADMIN',\s*'REAL_ESTATE_AGENT'";         To = "'REAL_ESTATE_AGENT'" },
  @{ From = "'SUPER_ADMIN',\s*'MAINTENANCE_PROVIDER'";      To = "'MAINTENANCE_PROVIDER'" },
  @{ From = "'SUPER_ADMIN',\s*'OWNER',\s*'ACCOUNTANT'";     To = "'OWNER','ACCOUNTANT'" },
  # MP-style (MercadoPago): TENANT, OWNER, SUPER_ADMIN
  @{ From = "'TENANT',\s*'OWNER',\s*'SUPER_ADMIN'"; To = "'TENANT','OWNER'" },
  # 2-role
  @{ From = "'SUPER_ADMIN',\s*'OWNER'"; To = "'OWNER'" },
  @{ From = "'OWNER',\s*'SUPER_ADMIN'"; To = "'OWNER'" },
  # Edge cases with no space or with extra spaces already covered by \s*
  # Collapse @PreAuthorize("hasAnyRole('OWNER')") → @PreAuthorize("hasRole('OWNER')")
  @{ From = 'hasAnyRole\(''OWNER''\)'; To = "hasRole('OWNER')" },
  @{ From = 'hasAnyRole\(''REAL_ESTATE_AGENT''\)'; To = "hasRole('REAL_ESTATE_AGENT')" },
  @{ From = 'hasAnyRole\(''MAINTENANCE_PROVIDER''\)'; To = "hasRole('MAINTENANCE_PROVIDER')" }
)

$touched = 0
$diff = @()
foreach ($f in $categoryB) {
  $full = Join-Path $ctrlDir $f
  if (-not (Test-Path $full)) { Write-Warning "Skipping missing $f"; continue }
  $text = [System.IO.File]::ReadAllText($full)
  $orig = $text
  foreach ($r in $replacements) {
    $text = [regex]::Replace($text, $r.From, $r.To)
  }
  if ($text -ne $orig) {
    [System.IO.File]::WriteAllText($full, $text, (New-Object System.Text.UTF8Encoding $false))
    $touched++
    # Count hits diff
    $before = ([regex]::Matches($orig, 'SUPER_ADMIN')).Count
    $after  = ([regex]::Matches($text, 'SUPER_ADMIN')).Count
    $diff += "$f`: $before -> $after"
  }
}

Write-Host "Files modified: $touched"
$diff | ForEach-Object { Write-Host "  $_" }
