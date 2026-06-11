# ADMINDI — Plan Maestro Actualizado de Automatización Operativa y Contable

## Resumen
Estado consolidado y dirección correcta del producto:

- **Fase 0**: cerrada funcionalmente.
- **Fase 1**: cerrada funcionalmente; queda endurecimiento de cifrado en reposo.
- **Etapa 2**: cerrada para operación base de inmuebles, contratos, archivos y aprobaciones.
- **Etapa 3**: abierta y avanzada, pero no cerrada. Ya existe settlement automático, SPEI/CEP mock, convenios base, tenant portal y accountant portal. Falta convertir todo eso en un **centro operativo automático del inmueble**.

La siguiente implementación no debe abrir módulos aislados. Debe cerrar un bloque único:

**Cobranza + Convenios + Mantenimiento + Comercial + Timeline + Resumen mensual/anual inteligente por inmueble y por owner**

Regla rectora:
- el sistema debe operar solo con la app;
- `EMAIL` desde backend y `WHATSAPP` vía n8n solo amplifican;
- IA, si se usa, será **copiloto para resumen y clasificación**, no motor que cambie estados financieros sola.

## Estado Actual Consolidado
### Ya existe y se toma como base
- seguridad, MFA, refresh, contexto, auditoría y `ActionTask`;
- `SUPER_ADMIN`, `OWNER`, `PROPERTY_ADMIN`, `ACCOUNTANT`;
- inmuebles, contratos, archivos, fotos y planos;
- flujo `PROPERTY_ADMIN solicita borrar` → `OWNER aprueba`;
- settlement de pagos:
  - exacto,
  - parcial,
  - excedente;
- tenant portal con Mercado Pago y SPEI;
- convenios base:
  - solicitud,
  - aprobación/rechazo,
  - parcialidades;
- accountant portal con libro mayor, historial de pagos y export;
- providers de plataforma y multi-contexto.

### Falta para que el producto quede pulido
- el panel del dueño todavía no es la **bitácora viva del inmueble**;
- mantenimiento y actividad comercial todavía no están modelados como flujos completos;
- el resumen mensual/anual todavía no integra todo:
  - ingresos,
  - egresos,
  - pendientes,
  - convenios,
  - mantenimiento,
  - actividad comercial;
- cifrado en reposo existe pero no está endurecido para producción;
- CEP sigue mock.

## Implementación Objetivo
### 1. Centro Operativo del Inmueble
Crear una timeline/bitácora unificada por inmueble.

#### Nueva entidad
- `PropertyMovementEntity`

Campos mínimos:
- `id`
- `propertyId`
- `ownerId`
- `resourceType`
- `resourceId`
- `actorUserId`
- `actorRole`
- `eventType`
- `title`
- `description`
- `occurredAt`
- `metadataJson`
- `attachmentFileId` opcional

Eventos mínimos:
- `PROPERTY_CREATED`
- `PROPERTY_UPDATED`
- `PROPERTY_FILE_UPLOADED`
- `LEASE_CREATED`
- `LEASE_TERMINATED`
- `PAYMENT_VALIDATED`
- `PAYMENT_PARTIALLY_APPLIED`
- `PAYMENT_OVERPAID`
- `AGREEMENT_REQUESTED`
- `AGREEMENT_APPROVED`
- `AGREEMENT_REJECTED`
- `AGREEMENT_ACTIVE`
- `AGREEMENT_COMPLETED`
- `AGREEMENT_BREACHED`
- `MAINTENANCE_TICKET_CREATED`
- `MAINTENANCE_QUOTE_SUBMITTED`
- `MAINTENANCE_QUOTE_APPROVED`
- `MAINTENANCE_QUOTE_REJECTED`
- `MAINTENANCE_WORK_STARTED`
- `MAINTENANCE_WORK_COMPLETED`
- `MAINTENANCE_PAYMENT_RECORDED`
- `VACANCY_OPENED`
- `REAL_ESTATE_VISIT_RECORDED`
- `REAL_ESTATE_PHOTOS_UPLOADED`
- `REAL_ESTATE_COMMISSION_QUOTED`
- `REAL_ESTATE_COMMISSION_APPROVED`
- `REAL_ESTATE_COMMISSION_PAID`
- `VACANCY_CLOSED`

Regla:
- el detalle del inmueble debe consumir esta timeline;
- ya no se reconstruye historia desde métricas de invoices o listas de archivos.

### 2. Galería con contexto operativo
Enriquecer archivos del inmueble.

#### `PropertyFileDTO`
Agregar:
- `uploadedBy`
- `uploaderRole`
- `uploadedAt`
- `label`
- `note`

Etiquetas soportadas:
- `BASELINE`
- `UPDATE`
- `BEFORE`
- `AFTER`
- `VISIT`
- `MAINTENANCE`
- `VACANCY`
- `AGREEMENT_EVIDENCE`
- `OTHER`

Regla:
- una foto inicial del inmueble se marca `BASELINE`;
- fotos posteriores del agente o mantenimiento se marcan `UPDATE`, `VISIT`, `BEFORE`, `AFTER`, etc.;
- el dueño debe ver quién la subió, cuándo y para qué.

### 3. Mantenimiento como flujo contable y operativo
Crear módulo formal de mantenimiento.

#### Entidades
- `MaintenanceTicketEntity`
- `MaintenanceQuoteEntity`
- `MaintenanceWorkLogEntity`
- `MaintenanceEvidenceEntity`
- `ExpenseEntity` con `sourceType = MAINTENANCE`

#### Flujo
1. `TENANT` abre ticket desde portal.
2. El sistema asigna/notifica al proveedor de mantenimiento:
   - de plataforma o privado, según configuración del owner.
3. El proveedor visita, sube evidencia y cotización.
4. El `OWNER` aprueba o rechaza la cotización.
5. Solo si aprueba:
   - se registra egreso aprobado;
   - queda estado `APPROVED_PENDING_PAYMENT`.
6. Cuando se paga al proveedor:
   - el egreso pasa a `PAID`;
   - queda monto pagado, fecha y saldo pendiente si aplica.
7. Si el owner rechaza:
   - la cotización queda `REJECTED`;
   - no genera egreso contable.

#### Estados mínimos
Ticket:
- `OPEN`
- `ASSIGNED`
- `QUOTED`
- `APPROVED`
- `IN_PROGRESS`
- `COMPLETED`
- `REJECTED`
- `CANCELLED`

Quote/payment:
- `DRAFT`
- `SUBMITTED`
- `APPROVED_PENDING_PAYMENT`
- `PARTIALLY_PAID`
- `PAID`
- `REJECTED`

### 4. Comercial / vacancia / agente inmobiliario
Crear módulo formal para el agente inmobiliario.

#### Entidades
- `VacancyEntity`
- `RealEstateActivityEntity`
- `RealEstateQuoteEntity`
- `ExpenseEntity` con `sourceType = COMMERCIAL`

#### Flujo
1. Al terminar contrato o liberar inmueble:
   - el sistema abre vacancia automáticamente.
2. Se notifica al agente inmobiliario:
   - de plataforma o privado, según configuración.
3. El agente:
   - registra visita,
   - sube fotos,
   - añade observaciones,
   - sube cotización de comisión si aplica.
4. El `OWNER` aprueba o rechaza comisión/costo.
5. Solo lo aprobado genera egreso.
6. Cuando la vacancia cierra con nuevo arrendatario:
   - se cierra el ciclo comercial;
   - queda histórico del inmueble.

#### Estados mínimos
- `VACANCY_OPEN`
- `LISTING_ACTIVE`
- `VISIT_RECORDED`
- `COMMISSION_QUOTED`
- `COMMISSION_APPROVED`
- `COMMISSION_REJECTED`
- `COMMISSION_PAID`
- `VACANCY_CLOSED`

### 5. Convenios completos e integrados
El convenio ya existe; ahora debe integrarse plenamente a contabilidad, mora y reportes.

#### Reglas de negocio
- el tenant solicita convenio sobre factura o saldo pendiente;
- el owner aprueba o rechaza;
- si aprueba:
  - queda obligación original;
  - monto aprobado a pagar ahora;
  - monto diferido;
  - parcialidades futuras;
  - evidencia opcional;
  - estado del convenio;
  - impacto en resumen mensual y anual.

#### Evidencia del convenio
Agregar endpoint de upload:
- PDF
- imagen
- o sin archivo

Campos:
- `evidenceFileUrl`
- `evidenceType`
- `description` opcional

#### Estados
- `REQUESTED`
- `APPROVED`
- `REJECTED`
- `ACTIVE`
- `COMPLETED`
- `BREACHED`
- `CANCELLED`

#### Incumplimiento
Agregar proceso automático:
- si una parcialidad del convenio vence y no se paga, marcar:
  - installment `LATE`
  - agreement `BREACHED`
- generar movimiento del inmueble y alerta al owner.

### 6. Pago parcial: razón obligatoria y reglas de mora
Cuando un pago confirmado no liquide completamente la deuda, el sistema debe pedir razón al tenant.

#### Nueva clasificación
- `PARTIAL_SAME_MONTH`
- `PARTIAL_NEXT_MONTH`
- `REQUESTING_AGREEMENT`
- `BANK_ISSUE`
- `OTHER`

Campos mínimos en la justificación:
- `shortfallReason`
- `shortfallDescription` opcional
- `promisedCompletionDate` opcional

#### Reglas automáticas
- Si el pago parcial deja saldo y el tenant no ha dado razón:
  - crear `ActionTask` para tenant.
- Si el tenant indica `PARTIAL_SAME_MONTH`:
  - no se modifica convenio;
  - el saldo sigue abierto;
  - si no paga antes del vencimiento + gracia, aplica morosidad si está configurada.
- Si indica `PARTIAL_NEXT_MONTH`:
  - el sistema lo dirige a solicitud de convenio;
  - si no hay convenio aprobado, el saldo sigue sujeto a mora al vencer.
- Si hay convenio aprobado:
  - el monto diferido no se trata como mora hasta la fecha de sus parcialidades.
- Si el owner configuró morosidad en `TenantProfile`:
  - el sistema aplica recargo automático sobre saldos vencidos no cubiertos ni protegidos por convenio activo.

### 7. Resumen contable general del owner
Reemplazar `GlobalMetrics` superficial por un resumen financiero operativo.

#### Nuevo DTO
- `OwnerMonthlyAccountingSummaryDTO`

Campos mínimos:
- `monthYear`
- `expectedIncome`
- `collectedIncome`
- `outstandingIncome`
- `overpaidCredits`
- `approvedExpenses`
- `paidExpenses`
- `pendingExpenses`
- `lateFeeAccrued`
- `activeAgreementsCount`
- `breachedAgreementsCount`
- `delinquentTenantsCount`
- `propertiesWithIssuesCount`

#### Listados de detalle
- `receivables`
  - inmueble
  - arrendatario
  - total renta
  - pagado
  - pendiente
  - convenio o mora
- `expenses`
  - inmueble
  - tipo (`MAINTENANCE`, `COMMERCIAL`, `MANUAL`)
  - aprobado
  - pagado
  - pendiente
- `alerts`
  - mora
  - convenio incumplido
  - ticket abierto
  - vacancia activa

### 8. Resumen mensual y anual inteligente por inmueble
#### Nuevo DTO mensual
- `PropertyMonthlySummaryDTO`

Campos mínimos:
- `propertyId`
- `propertyName`
- `monthYear`
- `occupancyStatus`
- `expectedIncome`
- `collectedIncome`
- `outstandingIncome`
- `partialPaymentsCount`
- `activeAgreementsCount`
- `breachedAgreementsCount`
- `approvedMaintenanceExpense`
- `paidMaintenanceExpense`
- `approvedCommercialExpense`
- `paidCommercialExpense`
- `ticketsOpenCount`
- `vacancyStatus`
- `newPhotosCount`
- `keyEvents`

#### Nuevo DTO anual
- `PropertyAnnualSummaryDTO`

Campos mínimos:
- agregación 12 meses de ingresos, egresos, morosidad, convenios, mantenimiento, vacancias y actividad comercial.

#### IA en esta fase
Usar IA solo como copiloto opcional para:
- redactar resumen narrativo mensual por inmueble;
- clasificar tono/causa libre de convenios o pagos parciales;
- resaltar riesgos y anomalías.

Reglas:
- IA no cambia estados financieros;
- IA no aprueba pagos, convenios ni egresos;
- si IA falla, el sistema sigue funcionando con resumen determinístico.

### 9. APIs e interfaces a consolidar
#### Ampliar
- `/api/properties/{id}/files`
- `/api/ledger/*`
- `/api/payments/*`
- `/api/agreements/*`
- `/api/reports/monthly/*`

#### Nuevas
- `/api/properties/{id}/timeline`
- `/api/properties/{id}/summary/monthly`
- `/api/properties/{id}/summary/annual`
- `/api/agreements/{id}/evidence`
- `/api/maintenance/tickets`
- `/api/maintenance/quotes`
- `/api/maintenance/payments`
- `/api/vacancies`
- `/api/commercial-activity`
- `/api/owner/accounting-summary`
- `/api/payments/{invoiceId}/shortfall-reason`

## UI Objetivo
### Panel del dueño
Secciones por inmueble:
- `Resumen`
- `Galería`
- `Timeline`
- `Cobranza`
- `Convenios`
- `Mantenimiento`
- `Vacancia / Comercial`

### Dashboard general owner
Widgets:
- ingresos esperados / cobrados / pendientes
- egresos aprobados / pagados / pendientes
- propiedades con incidencias
- arrendatarios en mora
- convenios activos e incumplidos
- vacancias abiertas
- mantenimientos abiertos

### Tenant
- pagar por MP o SPEI
- si pago parcial:
  - pedir razón obligatoria
  - si corresponde, sugerir convenio
- solicitar convenio con evidencia opcional
- ver estado de convenio
- ver saldo pendiente / saldo a favor

### Accountant
- libro mayor con ingresos/egresos reales
- pagos con aplicado/no aplicado
- convenios y diferidos
- export mensual/anual

## Plan de pruebas
### Finanzas
- pago exacto
- dos pagos parciales acumulativos
- pago excedente
- saldo a favor
- razón de pago parcial obligatoria
- mora aplicada a saldo vencido sin convenio
- no mora sobre saldo cubierto por convenio activo

### Convenios
- tenant solicita convenio
- owner aprueba con parcialidades
- owner rechaza
- evidencia opcional
- acuerdo activo aparece en resumen
- acuerdo incumplido pasa a `BREACHED`

### Mantenimiento
- tenant abre ticket
- proveedor recibe tarea
- proveedor sube cotización y fotos
- owner aprueba → egreso aprobado
- owner rechaza → sin egreso
- pago al proveedor → egreso pagado
- timeline del inmueble refleja todo

### Comercial
- vacancia automática al terminar contrato
- agente recibe tarea
- agente sube fotos/visitas/cotización
- owner aprueba comisión → egreso
- vacancia cierra con nuevo arrendatario
- timeline del inmueble refleja todo

### Panel del dueño
- galería muestra autor, fecha y tipo
- timeline mezcla pagos, convenios, mantenimiento y comercial
- resumen mensual del inmueble coincide con libro mayor
- resumen general owner coincide con sumatoria de inmuebles

### Seguridad
- cifrado activo con `ENCRYPTION_KEY`
- fallo en entornos no-dev si falta key
- error claro si ciphertext no se puede descifrar con la clave
- MFA sigue funcionando con secreto cifrado

## Asunciones y defaults
- `OWNER` es aprobador normal de convenios, cotizaciones y egresos.
- `PROPERTY_ADMIN` consulta y opera, pero no aprueba gastos sensibles por default.
- egresos en esta fase incluyen:
  - mantenimiento,
  - actividad comercial/agente,
  - y soporte para `MANUAL` si se requiere capturar otros costos del inmueble.
- IA será opcional y solo de apoyo narrativo/analítico.
- `EMAIL` backend y `WHATSAPP` vía n8n siguen siendo complementarios, nunca dependencias.
- CEP real sigue pendiente; Etapa 3 no se considera cerrada hasta reemplazar el mock.
