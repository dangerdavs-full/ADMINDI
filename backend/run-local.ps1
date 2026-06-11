# Arranca el backend ADMINDI con perfiles local + secrets.
#
# Uso:
#   .\run-local.ps1              # arranca SIN sembrar datos QA (modo por defecto)
#   .\run-local.ps1 -Seed        # arranca Y siembra usuarios/propiedades QA Etapa 0
#
# ¿Por qué el seed está OFF por defecto?
#   El seeder crea tenants, staff y superadmin QA con IDs fijos para smoke tests.
#   Cuando estás probando flujos reales (crear un dueño nuevo, disparar WhatsApp,
#   etc.) esos registros solo estorban y ocupan emails/teléfonos. Por eso esta
#   versión del script NUNCA siembra salvo que pases `-Seed` explícitamente.
#
# Requisitos:
#   - backend/src/main/resources/application-secrets.yml con credenciales reales.
#   - Maven en PATH.
#   - Variables TWILIO_ACCOUNT_SID y TWILIO_AUTH_TOKEN definidas en el entorno
#     (o editar application-secrets.yml directamente).

param(
    [switch]$Seed
)

$env:SPRING_PROFILES_ACTIVE = "local,secrets"

# Sobrescribe el default del perfil `local` (que pone enabled=true).
# Con -Seed el usuario pide explícitamente sembrar; sin flag nunca se siembra.
if ($Seed) {
    $env:ADMINDI_QA_SEED_ENABLED = "true"
    Write-Host "[ADMINDI] Semilla QA: ACTIVADA (flag -Seed recibido)" -ForegroundColor Yellow
} else {
    $env:ADMINDI_QA_SEED_ENABLED = "false"
    Write-Host "[ADMINDI] Semilla QA: desactivada (usa -Seed para activarla)" -ForegroundColor DarkGray
}

# Si tienes las credenciales de Twilio, descomenta y pega aquí:
# $env:TWILIO_ACCOUNT_SID = "ACxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
# $env:TWILIO_AUTH_TOKEN  = "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"

Write-Host "[ADMINDI] Arrancando backend con perfiles: $env:SPRING_PROFILES_ACTIVE" -ForegroundColor Cyan

mvn spring-boot:run
