# Registro maestro — 35 plantillas WhatsApp aprobadas

Este archivo consolida las 35 plantillas aprobadas en Twilio/Meta para ADMINDI, con:

- nombre final aprobado
- Content SID (`HX...`)
- body final operativo/aprobado
- variables de ejemplo (`Sample values`)

Convención de ejemplo usada en este registro:

- URLs de ejemplo con `https://arfaxadmindware.com.mx`
- `Language`: `Spanish`
- `Content type`: `Text`
- `Category`: `Utility`

---

## Resumen de SIDs

| # | Plantilla aprobada | Content SID |
|---|---|---|
| 1 | `admindi_owner_welcome_v2` | `HXddc8ce4292e4933e107e30c254462590` |
| 2 | `admindi_tenant_welcome_v2` | `HX07cb001df7b63655ab3fb67622cfe79e` |
| 3 | `admindi_owner_profile_updated_v3` | `HX4eb0c265334423af2118f7a247f83a2a` |
| 4 | `admindi_payment_reminder_5d_v1` | `HXac97f3585f94cf1a224a209f3ee8e436` |
| 5 | `admindi_payment_reminder_3d_v1` | `HX5b90af984e990de9cf10fe8bddcd5379` |
| 6 | `admindi_payment_reminder_2d_v1` | `HX6ce9f3f64088fbdf6d6f04e761dc2fa1` |
| 7 | `admindi_payment_reminder_1d_v1` | `HXa03762776cdca96548908db47cfda4ad` |
| 8 | `admindi_payment_reminder_manual_v1` | `HX7160eca1a82ca16d692805d62adcbb76` |
| 9 | `admindi_transfer_confirmed_owner_v2` | `HXb44fe328103ffbf99459b32b244fa9de` |
| 10 | `admindi_unpaid_digest_v2` | `HX0effd135079bc25238426aa18f57627e` |
| 11 | `admindi_monthly_report_v2` | `HX6e77e194c33f68305020006129bf45c5` |
| 12 | `admindi_maintenance_ticket_awaiting_owner_auth_v1` | `HXbceb0b86caf0362f35393140ebb5b7ef` |
| 13 | `admindi_maintenance_ticket_rejected_by_owner_v1` | `HX734bc2a13811adfde6e5e3791eba71b7` |
| 14 | `admindi_maintenance_provider_rejected_v1` | `HX160083563cc45fda746865f85f22ab0e` |
| 15 | `admindi_maintenance_ticket_assigned_v1` | `HX67f29bef07cedd51d2148b72c60115be` |
| 16 | `admindi_maintenance_quote_uploaded_v1` | `HX5908beff6f45d2f6d3c6c7b2b3e3217a` |
| 17 | `admindi_maintenance_payment_required_v1` | `HX653848c87dd8a8047f0ac31b9974a440` |
| 18 | `admindi_property_vacancy_opened_v1` | `HX22a3097b94ae17903492d60f6f1445ce` |
| 19 | `admindi_vacancy_agent_assigned_v1` | `HX8e077bc64308292d7301d646df4f1c6e` |
| 20 | `admindi_vacancy_agent_needed_v1` | `HX7fc93a70b48e920c42cc9cd95f6b481c` |
| 21 | `admindi_vacancy_agent_rejected_v1` | `HXab62b05512ea74037f94185836815e09` |
| 22 | `admindi_vacancy_agent_timeout_v1` | `HX64b668e7eae846b724c6976a7efaa7e1` |
| 23 | `admindi_vacancy_photos_uploaded_v1` | `HX5182ebeba469bf93caa99783c968dd8b` |
| 24 | `admindi_vacancy_chain_exhausted_v1` | `HX47c1f7000523d0c701309b5fea267b60` |
| 25 | `admindi_prospect_proposed_v1` | `HXab0fad96c0ff14bf4ed4060fee75da8c` |
| 26 | `admindi_prospect_reminder_v1` | `HXd176fe24467f92bb90d07841ce35dbc6` |
| 27 | `admindi_prospect_owner_accepted_v1` | `HX37e56cbd175bd7f1537e53bdbece777a` |
| 28 | `admindi_prospect_owner_rejected_v1` | `HX06522908c1edce726aa900a034366eac` |
| 29 | `admindi_contract_signed_commission_due_v1` | `HX9f801d527720d17e60b99d358a43d064` |
| 30 | `admindi_commission_approved_v1` | `HX4ba095fea1eb215567e57f0399a6fba2` |
| 31 | `admindi_commission_spei_pending_manual_v1` | `HXe18a598e721a4513ffa047674a06eb33` |
| 32 | `admindi_commission_paid_v1` | `HXc235043fce0c22654639fb3aca36b0ad` |
| 33 | `admindi_agent_bank_account_validated_v1` | `HXf32c74c35ad5a2db9b7e8b6282d3c96c` |
| 34 | `admindi_agent_bank_account_failed_v1` | `HX74666ad6586776ef787be84073c5007c` |
| 35 | `admindi_new_agentprovider` | `HX4f9149917cc7abb6a475d21850ef24ad` |

---

## Bloque inicial

### 1. `admindi_owner_welcome_v2`
**Content SID**: `HXddc8ce4292e4933e107e30c254462590`

**Body**
```text
Hola {{1}}, tu cuenta de ADMINDI fue activada para administrar tus inmuebles.

Correo registrado: {{2}}.
Para completar tu acceso, revisa el correo que enviamos con las instrucciones de ingreso.

Portal: {{3}}

Desde ahí podrás registrar inmuebles, configurar tu cuenta bancaria y administrar tus notificaciones.
```

**Sample values**
```text
{{1}} = Israel
{{2}} = israel@ejemplo.com
{{3}} = https://arfaxadmindware.com.mx
```

### 2. `admindi_tenant_welcome_v2`
**Content SID**: `HX07cb001df7b63655ab3fb67622cfe79e`

**Body**
```text
Hola {{1}}, tu expediente de arrendamiento en ADMINDI fue activado para el inmueble {{2}}.

Correo registrado: {{3}}.
Para completar tu acceso, revisa el correo que enviamos con las instrucciones de ingreso.

Portal: {{4}}

Desde ahí podrás consultar comprobantes, registrar tu pago y recibir recordatorios relacionados con tu renta.
```

**Sample values**
```text
{{1}} = David
{{2}} = Depto Reforma 201
{{3}} = david@ejemplo.com
{{4}} = https://arfaxadmindware.com.mx
```

### 3. `admindi_owner_profile_updated_v3`
**Content SID**: `HX4eb0c265334423af2118f7a247f83a2a`

**Body**
```text
Hola {{1}}, confirmamos que el {{2}} se actualizó información de tu perfil en ADMINDI.

Campos modificados: {{3}}.

Este mensaje corresponde al cambio registrado en tu cuenta. Puedes revisar el detalle en {{4}} cuando lo necesites.
```

**Sample values**
```text
{{1}} = Israel
{{2}} = 17/04/2026 10:15
{{3}} = CLABE, teléfono de contacto
{{4}} = https://arfaxadmindware.com.mx
```

### 4. `admindi_payment_reminder_5d_v1`
**Content SID**: `HXac97f3585f94cf1a224a209f3ee8e436`

**Body**
```text
Hola {{1}}, te recordamos que tu renta de {{2}} por ${{3}} vence el {{4}} (faltan 5 días).

Para pagar por SPEI usa estos datos:
Banco: {{5}}
CLABE: {{6}}

Si ya pagaste, puedes ignorar este mensaje.
```

**Sample values**
```text
{{1}} = David
{{2}} = Depto Reforma 201
{{3}} = 18,500.00
{{4}} = 22/04/2026
{{5}} = BBVA
{{6}} = 012180001234567890
```

### 5. `admindi_payment_reminder_3d_v1`
**Content SID**: `HX5b90af984e990de9cf10fe8bddcd5379`

**Body**
```text
Hola {{1}}, tu renta de {{2}} por ${{3}} vence el {{4}} (faltan 3 días).

Para pagar por SPEI:
Banco: {{5}}
CLABE: {{6}}

Si ya pagaste, ignora este mensaje.
```

**Sample values**
```text
{{1}} = David
{{2}} = Depto Reforma 201
{{3}} = 18,500.00
{{4}} = 22/04/2026
{{5}} = BBVA
{{6}} = 012180001234567890
```

### 6. `admindi_payment_reminder_2d_v1`
**Content SID**: `HX6ce9f3f64088fbdf6d6f04e761dc2fa1`

**Body**
```text
Hola {{1}}, tu renta de {{2}} por ${{3}} vence el {{4}} (faltan 2 días).

Para pagar por SPEI:
Banco: {{5}}
CLABE: {{6}}

Si ya pagaste, ignora este mensaje.
```

**Sample values**
```text
{{1}} = David
{{2}} = Depto Reforma 201
{{3}} = 18,500.00
{{4}} = 22/04/2026
{{5}} = BBVA
{{6}} = 012180001234567890
```

### 7. `admindi_payment_reminder_1d_v1`
**Content SID**: `HXa03762776cdca96548908db47cfda4ad`

**Body**
```text
Hola {{1}}, tu renta de {{2}} por ${{3}} vence mañana ({{4}}).

Para pagar por SPEI:
Banco: {{5}}
CLABE: {{6}}

Si ya pagaste, ignora este mensaje.
```

**Sample values**
```text
{{1}} = David
{{2}} = Depto Reforma 201
{{3}} = 18,500.00
{{4}} = 22/04/2026
{{5}} = BBVA
{{6}} = 012180001234567890
```

### 8. `admindi_payment_reminder_manual_v1`
**Content SID**: `HX7160eca1a82ca16d692805d62adcbb76`

**Body**
```text
Hola {{1}}, te enviamos un recordatorio de pago pendiente de tu renta en {{2}}.

Monto por pagar: ${{3}}
Fecha de vencimiento: {{4}}

Datos para transferencia SPEI:
Banco: {{5}}
CLABE: {{6}}

Si ya realizaste el pago, por favor ignora este mensaje o envíanos tu comprobante.
```

**Sample values**
```text
{{1}} = David
{{2}} = Depto Reforma 201
{{3}} = 18,500.00
{{4}} = 22/04/2026
{{5}} = BBVA
{{6}} = 012180001234567890
```

### 9. `admindi_transfer_confirmed_owner_v2`
**Content SID**: `HXb44fe328103ffbf99459b32b244fa9de`

**Body**
```text
Hola {{1}}, se validó un pago SPEI a tu cuenta el {{2}}.

Inmueble: {{3}}
Arrendatario: {{4}}
Monto: ${{5}}
Clave de rastreo: {{6}}

Puedes consultar el detalle en tu portal: {{7}} para revisar el movimiento.
```

**Sample values**
```text
{{1}} = Israel
{{2}} = 17/04/2026 11:42
{{3}} = Depto Reforma 201
{{4}} = David Rodríguez
{{5}} = 18,500.00
{{6}} = MBAN01202604170000000123456789
{{7}} = https://arfaxadmindware.com.mx
```

### 10. `admindi_unpaid_digest_v2`
**Content SID**: `HX0effd135079bc25238426aa18f57627e`

**Body**
```text
Hola {{1}}, tienes {{2}} factura(s) vencida(s) al día de hoy por un total de ${{3}}.

Arrendatarios con pago pendiente: {{4}}

Consulta el detalle y envía recordatorios manuales desde: {{5}} para dar seguimiento.
```

**Sample values**
```text
{{1}} = Israel
{{2}} = 3
{{3}} = 42,800.00
{{4}} = David R. (Reforma 201), María L. (Insurgentes 45), Carlos G. (Tlalpan 120)
{{5}} = https://arfaxadmindware.com.mx/owner/delinquency
```

### 11. `admindi_monthly_report_v2`
**Content SID**: `HX6e77e194c33f68305020006129bf45c5`

**Body**
```text
Hola {{1}}, tu reporte mensual de {{2}} ya está disponible.

Resumen:
Ingresos cobrados: ${{3}}
Pendiente por cobrar: ${{4}}
Gastos registrados: ${{5}}
Ocupación: {{6}}

Descarga el PDF desde tu portal: {{7}} y revisa el resumen completo.
```

**Sample values**
```text
{{1}} = Israel
{{2}} = Marzo 2026
{{3}} = 87,500.00
{{4}} = 18,500.00
{{5}} = 12,340.00
{{6}} = 4 de 5 unidades ocupadas (80%)
{{7}} = https://arfaxadmindware.com.mx/owner/reports
```

---

## Fase 2 — nuevas plantillas aprobadas

### 12. `admindi_maintenance_ticket_awaiting_owner_auth_v1`
**Content SID**: `HXbceb0b86caf0362f35393140ebb5b7ef`

**Body**
```text
Hola {{1}}, tu inquilino abrió un ticket de mantenimiento en {{2}}.

Asunto: {{3}}
Urgencia: {{4}}

Entra a tu portal para autorizarlo y elegir proveedor en la pestaña Bandeja de decisiones: {{5}} para revisarlo.
```

**Sample values**
```text
{{1}} = Israel
{{2}} = Depto Reforma 201
{{3}} = Fuga en tubería del baño
{{4}} = ALTA
{{5}} = https://arfaxadmindware.com.mx/owner
```

### 13. `admindi_maintenance_ticket_rejected_by_owner_v1`
**Content SID**: `HX734bc2a13811adfde6e5e3791eba71b7`

**Body**
```text
Hola {{1}}, el dueño revisó tu ticket de mantenimiento {{2}} en {{3}} y no lo autorizó en este momento.

Motivo del rechazo: {{4}}

Puedes consultar el detalle o abrir un nuevo ticket desde tu portal: {{5}} para dar seguimiento.
```

**Sample values**
```text
{{1}} = David
{{2}} = Pintura de sala
{{3}} = Depto Reforma 201
{{4}} = Fuera de cobertura del contrato
{{5}} = https://arfaxadmindware.com.mx/tenant
```

### 14. `admindi_maintenance_provider_rejected_v1`
**Content SID**: `HX160083563cc45fda746865f85f22ab0e`

**Body**
```text
Hola {{1}}, el proveedor {{2}} no aceptó tu ticket {{3}} en {{4}}.

Siguiente paso: {{5}}

Consulta el estado en tu portal: {{6}} para dar seguimiento.
```

**Sample values**
```text
{{1}} = Israel
{{2}} = Plomería López
{{3}} = Fuga en baño principal
{{4}} = Depto Reforma 201
{{5}} = Notificamos al siguiente proveedor de tu lista: Mantenimientos Díaz.
{{6}} = https://arfaxadmindware.com.mx/owner
```

### 15. `admindi_maintenance_ticket_assigned_v1`
**Content SID**: `HX67f29bef07cedd51d2148b72c60115be`

**Body**
```text
Hola {{1}}, tienes un ticket de mantenimiento asignado.

Inmueble: {{2}}
Asunto: {{3}}
Urgencia: {{4}}

Tienes 72 horas para aceptar o rechazar desde tu portal: {{5}} y responder a tiempo.
```

**Sample values**
```text
{{1}} = Plomería López
{{2}} = Depto Reforma 201
{{3}} = Fuga en baño
{{4}} = ALTA
{{5}} = https://arfaxadmindware.com.mx/provider
```

### 16. `admindi_maintenance_quote_uploaded_v1`
**Content SID**: `HX5908beff6f45d2f6d3c6c7b2b3e3217a`

**Body**
```text
Hola {{1}}, el proveedor {{2}} subió la cotización del ticket {{3}} en {{4}}.

Monto propuesto: ${{5}}

Revísala y aprueba o rechaza desde tu portal: {{6}} para continuar.
```

**Sample values**
```text
{{1}} = Israel
{{2}} = Plomería López
{{3}} = Fuga en baño
{{4}} = Depto Reforma 201
{{5}} = 3,500.00
{{6}} = https://arfaxadmindware.com.mx/owner
```

### 17. `admindi_maintenance_payment_required_v1`
**Content SID**: `HX653848c87dd8a8047f0ac31b9974a440`

**Body**
```text
Hola {{1}}, aprobaste la cotización de {{2}} por ${{3}} para el ticket {{4}}.

Detalle: {{5}}

Registra el pago SPEI y sube el comprobante desde tu portal: {{6}} para completar el proceso.
```

**Sample values**
```text
{{1}} = Israel
{{2}} = Plomería López
{{3}} = 3,500.00
{{4}} = Fuga en baño
{{5}} = La plataforma absorbe 15% (crédito: $525.00).
{{6}} = https://arfaxadmindware.com.mx/owner
```

### 18. `admindi_property_vacancy_opened_v1`
**Content SID**: `HX22a3097b94ae17903492d60f6f1445ce`

**Body**
```text
Hola {{1}}, se te asignó un nuevo inmueble disponible para comercialización.

Inmueble: {{2}}
Dirección: {{3}}
Renta mensual esperada: ${{4}}

Tienes 72 horas para aceptar o rechazar antes de que pase al siguiente agente de la lista: {{5}} para responder.
```

**Sample values**
```text
{{1}} = Laura Martínez
{{2}} = Depto Reforma 201
{{3}} = Reforma 201, CDMX
{{4}} = 18,500.00
{{5}} = https://arfaxadmindware.com.mx/agent
```

### 19. `admindi_vacancy_agent_assigned_v1`
**Content SID**: `HX8e077bc64308292d7301d646df4f1c6e`

**Body**
```text
Hola {{1}}, el agente {{2}} aceptó tomar la vacancia de {{3}}.

Seguirá el proceso de levantamiento de fotos, prospección y cierre. Recibirás avisos cuando proponga un inquilino.

Consulta el detalle en tu portal: {{4}} para dar seguimiento.
```

**Sample values**
```text
{{1}} = Israel
{{2}} = Laura Martínez
{{3}} = Depto Reforma 201
{{4}} = https://arfaxadmindware.com.mx/owner
```

### 20. `admindi_vacancy_agent_needed_v1`
**Content SID**: `HX7fc93a70b48e920c42cc9cd95f6b481c`

**Body**
```text
Hola {{1}}, necesitamos agente comercial para el inmueble {{2}}.

Configura tus prioridades en Equipo y proveedores para activar la cadena, o asigna uno manualmente desde: {{3}} para resolverlo.
```

**Sample values**
```text
{{1}} = Israel
{{2}} = Depto Reforma 201
{{3}} = https://arfaxadmindware.com.mx/owner
```

### 21. `admindi_vacancy_agent_rejected_v1`
**Content SID**: `HXab62b05512ea74037f94185836815e09`

**Body**
```text
Hola {{1}}, el agente {{2}} no aceptó la vacancia de {{3}}.

Siguiente paso: {{4}}

Sigue el estado en tu portal: {{5}} para dar seguimiento.
```

**Sample values**
```text
{{1}} = Israel
{{2}} = Laura Martínez
{{3}} = Depto Reforma 201
{{4}} = Pasamos al siguiente agente de tu lista: Carlos Díaz.
{{5}} = https://arfaxadmindware.com.mx/owner
```

### 22. `admindi_vacancy_agent_timeout_v1`
**Content SID**: `HX64b668e7eae846b724c6976a7efaa7e1`

**Body**
```text
Hola {{1}}, se agotó el plazo de 72 horas para responder a la vacancia de {{2}}.

Estado actual: {{3}}

Detalle en tu portal: {{4}} para dar seguimiento.
```

**Sample values**
```text
{{1}} = Israel
{{2}} = Depto Reforma 201
{{3}} = Pasamos al siguiente agente de la cadena.
{{4}} = https://arfaxadmindware.com.mx/owner
```

### 23. `admindi_vacancy_photos_uploaded_v1`
**Content SID**: `HX5182ebeba469bf93caa99783c968dd8b`

**Body**
```text
Hola {{1}}, el agente {{2}} subió {{3}} fotos de {{4}} y dejó el inmueble listo para prospección.

Revisa las fotos desde tu portal y aprueba la publicación: {{5}} para continuar.
```

**Sample values**
```text
{{1}} = Israel
{{2}} = Laura Martínez
{{3}} = 12
{{4}} = Depto Reforma 201
{{5}} = https://arfaxadmindware.com.mx/owner
```

### 24. `admindi_vacancy_chain_exhausted_v1`
**Content SID**: `HX47c1f7000523d0c701309b5fea267b60`

**Body**
```text
Hola {{1}}, ningún agente de tu lista de prioridades aceptó la vacancia de {{2}}.

Puedes agregar más agentes en Equipo y proveedores o reiniciar la cadena desde tu portal: {{3}} para continuar.
```

**Sample values**
```text
{{1}} = Israel
{{2}} = Depto Reforma 201
{{3}} = https://arfaxadmindware.com.mx/owner
```

### 25. `admindi_prospect_proposed_v1`
**Content SID**: `HXab0fad96c0ff14bf4ed4060fee75da8c`

**Body**
```text
Hola {{1}}, el agente {{2}} te propuso un prospecto de inquilino para {{3}}.

Prospecto: {{4}}
Contacto: {{5}}

Revisa y decide desde la Bandeja de decisiones: {{6}} para tomar una decisión.
```

**Sample values**
```text
{{1}} = Israel
{{2}} = Laura Martínez
{{3}} = Depto Reforma 201
{{4}} = Jorge Ramírez
{{5}} = 55 1234 5678 · jorge@ejemplo.com
{{6}} = https://arfaxadmindware.com.mx/owner
```

### 26. `admindi_prospect_reminder_v1`
**Content SID**: `HXd176fe24467f92bb90d07841ce35dbc6`

**Body**
```text
Hola {{1}}, sigue pendiente tu decisión sobre el prospecto {{2}} para {{3}}.

Tiempo transcurrido: {{4}}

Acepta o rechaza desde: {{5}} cuando lo consideres.
```

**Sample values**
```text
{{1}} = Israel
{{2}} = Jorge Ramírez
{{3}} = Depto Reforma 201
{{4}} = 2 días
{{5}} = https://arfaxadmindware.com.mx/owner
```

### 27. `admindi_prospect_owner_accepted_v1`
**Content SID**: `HX37e56cbd175bd7f1537e53bdbece777a`

**Body**
```text
Hola {{1}}, el dueño aceptó al prospecto {{2}} para {{3}}.

Coordina la firma del contrato y, al cerrar, sube la evidencia desde tu portal: {{4}} para continuar.
```

**Sample values**
```text
{{1}} = Laura Martínez
{{2}} = Jorge Ramírez
{{3}} = Depto Reforma 201
{{4}} = https://arfaxadmindware.com.mx/agent
```

### 28. `admindi_prospect_owner_rejected_v1`
**Content SID**: `HX06522908c1edce726aa900a034366eac`

**Body**
```text
Hola {{1}}, el dueño rechazó al prospecto {{2}}.

Motivo: {{3}}

El inmueble vuelve a estado disponible para que propongas a otro interesado desde: {{4}} cuando lo necesites.
```

**Sample values**
```text
{{1}} = Laura Martínez
{{2}} = Jorge Ramírez
{{3}} = Ingresos no suficientes para el contrato
{{4}} = https://arfaxadmindware.com.mx/agent
```

### 29. `admindi_contract_signed_commission_due_v1`
**Content SID**: `HX9f801d527720d17e60b99d358a43d064`

**Body**
```text
Hola {{1}}, se registró la firma del contrato para {{2}} con el agente {{3}}.

Comisión pendiente de pago: ${{4}}

Realiza la transferencia SPEI y sube el comprobante desde la Bandeja de decisiones: {{5}} para completar el pago.
```

**Sample values**
```text
{{1}} = Israel
{{2}} = Depto Reforma 201
{{3}} = Laura Martínez
{{4}} = 8,325.00
{{5}} = https://arfaxadmindware.com.mx/owner
```

### 30. `admindi_commission_approved_v1`
**Content SID**: `HX4ba095fea1eb215567e57f0399a6fba2`

**Body**
```text
Hola {{1}}, el dueño aprobó tu comisión por la comercialización de {{2}}.

Monto: ${{3}}

Te avisaremos cuando reciba el pago. Revisa el estado en: {{4}} cuando lo necesites.
```

**Sample values**
```text
{{1}} = Laura Martínez
{{2}} = Depto Reforma 201
{{3}} = 8,325.00
{{4}} = https://arfaxadmindware.com.mx/agent
```

### 31. `admindi_commission_spei_pending_manual_v1`
**Content SID**: `HXe18a598e721a4513ffa047674a06eb33`

**Body**
```text
Hola {{1}}, el dueño registró el pago de tu comisión por ${{2}}, pero la validación automática falló.

Clave de rastreo SPEI: {{3}}

Por favor confirma manualmente si recibiste el depósito desde tu portal: {{4}} para validar el estado.
```

**Sample values**
```text
{{1}} = Laura Martínez
{{2}} = 8,325.00
{{3}} = MBAN01202604180000000123456789
{{4}} = https://arfaxadmindware.com.mx/agent
```

### 32. `admindi_commission_paid_v1`
**Content SID**: `HXc235043fce0c22654639fb3aca36b0ad`

**Body**
```text
Hola {{1}}, la comisión de la comercialización de {{2}} quedó liquidada.

Monto: ${{3}}
Fecha: {{4}}

Consulta el expediente en tu portal: {{5}} para referencia futura.
```

**Sample values**
```text
{{1}} = Laura Martínez
{{2}} = Depto Reforma 201
{{3}} = 8,325.00
{{4}} = 18/04/2026
{{5}} = https://arfaxadmindware.com.mx/agent
```

### 33. `admindi_agent_bank_account_validated_v1`
**Content SID**: `HXf32c74c35ad5a2db9b7e8b6282d3c96c`

**Body**
```text
Hola {{1}}, tu CLABE terminada en {{2}} del banco {{3}} quedó registrada y validada correctamente.

Ya puedes recibir pagos automatizados en ADMINDI.
```

**Sample values**
```text
{{1}} = Laura Martínez
{{2}} = 7890
{{3}} = BBVA
```

### 34. `admindi_agent_bank_account_failed_v1`
**Content SID**: `HX74666ad6586776ef787be84073c5007c`

**Body**
```text
Hola {{1}}, no pudimos validar la CLABE que registraste.

Motivo: {{2}}

Revísala y vuelve a guardarla desde tu portal: {{3}} para corregirla.
```

**Sample values**
```text
{{1}} = Laura Martínez
{{2}} = La CLABE no tiene 18 dígitos válidos
{{3}} = https://arfaxadmindware.com.mx/agent
```

### 35. `admindi_new_agentprovider`
**Content SID**: `HX4f9149917cc7abb6a475d21850ef24ad`
**Aprobada**: 20-abr-2026

Cubre onboarding de REAL_ESTATE_AGENT y MAINTENANCE_PROVIDER. El correo al user lleva las credenciales reales (usuario + contraseña temporal); el WhatsApp es teaser — no incluye credenciales por política de seguridad.

**Body** (pendiente de verificar — el operador aprobó en Twilio Console; este texto coincide con el contrato de 3 slots registrado en el backend)
```text
Hola {{1}}, fuiste registrado en ADMINDI.

Correo registrado: {{2}}.
Te enviamos a ese correo tu usuario y contraseña temporal para tu primer ingreso.

Portal: {{3}}

Desde ahí podrás operar tu cuenta y configurar cómo quieres recibir notificaciones.
```

**Sample values**
```text
{{1}} = Laura Martínez
{{2}} = laura@ejemplo.com
{{3}} = https://arfaxadmindware.com.mx
```

**Cableo técnico**
- `application.yml` → `twilio.templates.agent-welcome` y `twilio.templates.staff-welcome` apuntan al mismo SID.
- Eventos en el dispatcher: `AGENT_WELCOME` (rol REAL_ESTATE_AGENT) y `STAFF_WELCOME` (rol MAINTENANCE_PROVIDER / PROPERTY_ADMIN / ACCOUNTANT).
- El correo sigue llevando el cuerpo completo con credenciales; el WhatsApp usa esta plantilla (fuera de ventana 24h igual sale).
