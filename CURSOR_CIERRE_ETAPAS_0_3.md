# Brief Para Cursor — Cierre Real Etapas 0, 1, 2 y 3

## Rol
Actúa como principal engineer full-stack con foco fuerte en seguridad, trazabilidad financiera y cierre de producto. No rediseñes el dominio desde cero. Implementa sobre la base actual y corrige cualquier contradicción con estas reglas.

## Fuente de verdad
- `C:\Users\Arfax\Desktop\plataforma inmubles\ADMINDI_PLAN_FINAL_DEFINITIVO.md`
- `C:\Users\Arfax\Desktop\plataforma inmubles\PLAN_FINAL_5_ETAPAS.md`

Si el código actual contradice este brief, se ajusta el código. Si un plan viejo contradice el plan final, gana el plan final.

## Reglas no negociables
1. El activo arrendable es `Property`. No existe módulo funcional de `Unit/Unidades`.
2. Cada inmueble se identifica por dirección completa. No se requiere subdividirlo en unidades.
3. El expediente del tenant y el expediente mensual del inmueble son centros de operación.
4. Cada `OWNER` tiene su propia configuración de cobro:
   - cuenta bancaria receptora oficial;
   - credenciales y webhook de Mercado Pago por owner;
   - parámetros de conciliación y evidencias.
5. Todo pago debe guardar origen de verificación:
   - `MP_WEBHOOK`
   - `CEP_BANXICO`
   - `BANK_STATEMENT_MATCH`
   - `MANUAL_OVERRIDE`
6. Todo SPEI validado debe guardar artefactos oficiales disponibles:
   - XML CEP
   - PDF/acuse CEP
   - payload o respuesta oficial equivalente
   - hash y metadata de descarga
7. Todo pago en efectivo debe poder marcarse como reflejado o no en estado de cuenta, con evidencia y actor que confirmó.
8. Todo evento importante genera notificación en app. Los canales visibles para usuario son `WHATSAPP`, `EMAIL` y `en app`; `EMAIL` lo entrega el backend solo como salida y `WHATSAPP` usa n8n solo como adapter técnico bidireccional.
9. MFA, JWT RS256, refresh rotation, revocación e idempotencia deben quedar listos para producción.
10. No se aceptan mocks para lo que declaremos cerrado en Etapa 3.
11. `contactEmail` es el destinatario operativo del canal `EMAIL`; `loginEmail` no se usa como destino de correo de plataforma.
12. La extracción de datos SPEI desde imagen/PDF debe ser automática usando IA/OCR/visión antes de pedir datos manuales.

## Objetivo de cierre
Cerrar Etapas 0, 1, 2 y 3 al 100% real, no “demo-completo”.

## Alcance por bloque

### Etapa 0 — Seguridad base y hardening
- Revisar y endurecer auth, JWT, refresh rotation, reuse detection, MFA, reauth y rate limits.
- Validar cifrado en reposo de secretos críticos.
- Asegurar separación entre identidad de acceso y datos de contacto.
- Revisar headers, CORS, HMAC webhooks, replay protection, audit log, permisos y IDOR.
- Entregar pruebas automatizadas de seguridad y matrix mínima de regresión.

### Etapa 1 — Identidad, contexto, notificaciones y SUPER_ADMIN
- Cerrar selector de contexto multi-owner y switch-context.
- Dejar `OwnerMembership` como fuente única de contexto.
- Cerrar SUPER_ADMIN para:
  - owner CRUD;
  - purge segura;
  - proveedores/agentes de plataforma;
  - configuración global.
- Cerrar centro de preferencias de notificación:
  - notificación en app siempre activa;
  - `EMAIL` por evento y usuario desde backend;
  - `WHATSAPP` por evento y usuario vía n8n.
- Cerrar `Notification` + `ActionTask` end-to-end en backend y frontend.

### Etapa 2 — Inmueble, tenant, expediente y archive
- Eliminar `Unit` del flujo funcional:
  - sin navegación;
  - sin formularios;
  - sin dependencia de negocio en alta/edición/archive;
  - dejar solo compatibilidad técnica temporal si migración lo exige.
- Asegurar alta integral tenant sobre `propertyId`.
- Asegurar edición sin cambio directo de inmueble.
- Asegurar archive con:
  - cierre de lease;
  - inmueble disponible;
  - apertura de vacancia;
  - cancelación de invoice abierta;
  - revocación de membership cuando corresponda.
- Dejar expediente tenant usable y completo.

### Etapa 3 — Cobranza completa y verificable
- Mercado Pago real por owner:
  - configuración por owner;
  - webhook firmado;
  - conciliación automática;
  - pago con tarjeta marcado automático como correcto.
- SPEI con CEP real:
  - sin mock;
  - extracción automática de datos desde imagen/PDF con IA/OCR/visión;
  - captura/reintento de faltantes;
  - si la imagen no sirve o no es comprobante, pedir datos correctamente escritos;
  - reintento máximo 3 veces;
  - si no valida al tercer intento, alerta al owner indicando arrendatario e inmueble para confirmación manual de fondos;
  - descarga y preservación de XML/PDF/acuse/payload;
  - vinculación a pago, factura y expediente mensual del inmueble.
- Pago parcial:
  - razón obligatoria;
  - convenio cuando aplique;
  - mora automática según reglas.
- Efectivo:
  - registro auditado;
  - conciliación manual contra estado de cuenta;
  - estado reflejado/no reflejado.
- Resumen mensual por inmueble:
  - esperado;
  - cobrado;
  - pendiente;
  - método;
  - verificación;
  - evidencia descargable;
  - actor de confirmación.

## Modelo funcional obligatorio
- `Property` = activo arrendable principal.
- `TenantProfile` = expediente.
- `Lease ACTIVE` único por `propertyId`.
- `Invoice` mensual ligada a expediente activo.
- `Payment` con verificación y evidencia.
- `Property monthly dossier` por `monthYear` como vista consolidada del inmueble.

## Restricciones de implementación
- No agregues UI placeholder para etapas 0-3.
- No dejes flujos críticos detrás de mocks.
- No mantengas “Unidades” visibles en menú, endpoints nuevos ni documentación funcional.
- No mezcles lógica crítica en n8n; úsalo solo como salida técnica de `WHATSAPP`.
- No uses email como identificador de integración.

## Entregables obligatorios
1. Código backend.
2. Código frontend.
3. Migraciones SQL/Flyway.
4. Pruebas automatizadas reales.
5. Actualización de documentación técnica y funcional.
6. Lista de archivos cambiados.
7. Lista de riesgos residuales.

## Forma de entrega
Trabaja por bloques. Antes de codificar cada bloque:
1. enumera invariantes;
2. lista archivos a tocar;
3. define criterios de aceptación;
4. implementa;
5. ejecuta pruebas;
6. reporta qué quedó cerrado y qué no.

## Criterios de aceptación mínimos
- Compila backend y frontend.
- No rompe login/context switch.
- No rompe alta/archive de tenant.
- No deja referencias funcionales a `Unit`.
- Todo pago queda con método, estado, verificación y evidencia.
- `EMAIL` solo envía mensajes; `WHATSAPP` es el único canal externo bidireccional.
- Los mensajes entrantes de `WHATSAPP` se resuelven por `contactPhone` normalizado a `E.164`; si no hay match único/autorizado, pasan a revisión manual.
- Owner, admin autorizado y accountant ven la misma historia financiera.
- Las notificaciones internas sí se generan.
- `OWNER_CREATED` no aparece como evento configurable para usuario.
- Los webhooks no permiten replay fácil.

## Primer bloque a ejecutar
Empieza por un diagnóstico técnico corto de Etapa 0 y 1 sobre el código actual y luego implementa primero:
- selector de contexto;
- preferencias de notificación;
- eliminación funcional de `Unit` del producto;
- configuración de cobro por owner.
