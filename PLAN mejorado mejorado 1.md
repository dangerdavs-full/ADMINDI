# ADMINDI — Ajuste del plan principal: expediente operativo por inmueble, pago a proveedores/agentes con confirmación bilateral y visibilidad total para dueño/admin/contador

## Resumen
El plan base cambia en cuatro puntos de negocio:

1. **El inmueble (`Property`) es el activo arrendable principal.**
   - Ya no se modela la operación principal por `Unit/Unidades`.
   - El inmueble se identifica por **dirección completa** con número interior opcional.

2. **Alta de arrendatario = expediente activo + ocupación inmediata del inmueble.**
   - No es “pre-registro”.
   - Si el dueño/admin registra al arrendatario, es porque el trato ya ocurrió y el expediente entra activo.

3. **Baja de arrendatario = archivo del expediente + liberación del inmueble + apertura automática de vacancia.**
   - La vacancia dispara tareas internas y evento a n8n.
   - El agente inmobiliario asignado, sea de plataforma o privado, debe poder inspeccionar, subir fotos, registrar seguimiento y cerrar la vacancia cuando se consiga nuevo arrendatario.

4. **Pagos a mantenimiento y agentes ya no se manejan como gasto unilateral cerrado.**
   - Se debe modelar si el pago fue completo o parcial.
   - Debe existir **confirmación bilateral** entre plataforma/dueño-admin y proveedor/agente.
   - `ACCOUNTANT` y `PROPERTY_ADMIN` deben ver todos esos movimientos porque forman parte de la reportería operativa y contable.

## Cambios principales
### 1. Dominio operativo por inmueble
- `Lease` se conserva como nombre técnico si ayuda a la migración, pero depende de `propertyId` y no de un modelo funcional de unidad.
- No existe módulo funcional de `Unit`; cualquier rastro queda solo como legado temporal de migración.
- Reglas:
  - crear arrendatario ocupa inmueble;
  - archivar/bajar arrendatario libera inmueble;
  - solo puede haber **un expediente activo por inmueble**.
- `Property.status` queda gobernado por el expediente activo y por mantenimiento:
  - `OCCUPIED`
  - `AVAILABLE`
  - `MAINTENANCE`
  - `DELETED`

### 2. Expediente del arrendatario
- `POST /api/tenants` se vuelve un alta integral y transaccional:
  - crea `User` del arrendatario;
  - crea `TenantProfile`;
  - crea contrato/tenencia activa del inmueble;
  - sube PDF firmado opcional;
  - marca el inmueble como `OCCUPIED`;
  - genera movimientos y auditoría.
- El expediente del arrendatario incluye:
  - datos personales;
  - inmueble por dirección completa;
  - renta, depósito, día de pago;
  - contrato actual;
  - contratos históricos;
  - documentos;
  - convenios;
  - estado contable básico.
- Se elimina del menú principal del dueño la noción separada de “Contratos”.

### 3. Baja del arrendatario y vacancia automática
- La operación normal deja de ser “delete hard” y pasa a ser **archivo operativo**.
- Nuevo flujo:
  - `POST /api/tenants/{tenantProfileId}/archive`
  - payload mínimo:
    - `vacancyRoutingMode: PRIVATE | PLATFORM`
    - `reason` opcional
- Efectos transaccionales:
  - se archiva expediente;
  - se cierra la tenencia activa;
  - se libera el inmueble;
  - se crea vacancia;
  - se crea `ActionTask`;
  - se crea movimiento del inmueble;
  - se despacha evento a n8n si está configurado.
- Si el usuario quiere una purga dura futura, eso será un flujo aparte, no el operativo.

### 4. Vacancia y agentes inmobiliarios
- Al liberar un inmueble, la vacancia arranca seguimiento comercial real:
  - inspección;
  - fotos del estado del inmueble;
  - notas;
  - visitas;
  - publicación/puesta en renta;
  - comisión;
  - cierre con nuevo arrendatario.
- El dueño puede operar con agentes:
  - `PLATFORM`
  - `PRIVATE`
- La elección del origen de agente para vacancia se hace en el flujo de baja/liberación.
- Regla obligatoria:
  - si el dueño elige `PRIVATE` y no tiene agentes privados activos, el sistema **bloquea** la baja final y ofrece:
    - registrar/vincular agente privado;
    - o continuar con agentes de plataforma.
- El agente debe poder subir fotos y actividad comercial directamente al inmueble/vacancia.

### 5. Mantenimiento con preferencia del dueño
- El dueño configura una preferencia persistente:
  - `maintenanceRoutingMode: PRIVATE | PLATFORM`
- No se permite fallback silencioso si eligió privados y no tiene ninguno.
- Flujo:
  - se crea ticket;
  - se enruta según preferencia;
  - si no hay proveedor elegible, se crea tarea pendiente para dueño/admin;
  - el proveedor acepta;
  - sube cotización;
  - dueño o admin autorizado aprueba o rechaza dentro de la app;
  - se registra egreso;
  - se registra pago;
  - se confirma si quedó totalmente cubierto o con saldo pendiente.

### 6. Pago a agentes/proveedores con confirmación bilateral
Esto es nuevo y obligatorio.

#### Nuevo modelo de pago de egreso
- `Expense` ya no debe quedar solo en `QUOTED / APPROVED / REJECTED / PAID`.
- Se agrega capa de **settlement del egreso** con al menos:
  - `approvedAmount`
  - `paidAmount`
  - `outstandingAmount`
  - `paymentSettlementStatus: UNPAID | PARTIALLY_PAID | PAID_IN_FULL | DISPUTED`
  - `paymentMethod: SPEI | CASH | OTHER`
  - `providerConfirmationStatus: PENDING | CONFIRMED | DISPUTED`
  - `ownerConfirmationStatus: PENDING | CONFIRMED`
- El sistema debe soportar pago parcial a proveedor/agente.
- El sistema debe dejar trazado:
  - cuánto se aprobó;
  - cuánto se pagó;
  - cuánto quedó pendiente;
  - quién confirmó;
  - cuándo confirmó;
  - si hubo disputa.

#### Confirmación bilateral
- Cuando el dueño/admin registra un pago a proveedor/agente:
  - se crea una tarea interna para el proveedor/agente;
  - se crea un evento a n8n;
  - el proveedor/agente debe confirmar:
    - `me pagaron completo`
    - `me pagaron parcial`
    - `no corresponde / hay diferencia`
- Si ambas partes confirman completo:
  - `paymentSettlementStatus = PAID_IN_FULL`
- Si ambas reconocen parcial:
  - `paymentSettlementStatus = PARTIALLY_PAID`
- Si hay desacuerdo:
  - `paymentSettlementStatus = DISPUTED`
  - se crea `ActionTask` para owner/admin/accountant
  - n8n puede notificar, pero no resuelve la disputa.
- Esto aplica tanto para:
  - mantenimiento
  - comisiones/agentes inmobiliarios

### 7. Permisos de PROPERTY_ADMIN y visibilidad de ACCOUNTANT
- `PROPERTY_ADMIN` no debe autorizar por default todo.
- Se usa el sistema existente de plantillas/permisos para definir administradores con “privilegios altos”.
- Nuevas permissions mínimas:
  - `QUOTE_APPROVE`
  - `QUOTE_REJECT`
  - `EXPENSE_PAY`
  - `EXPENSE_SETTLEMENT_CONFIRM`
  - `VACANCY_ROUTE`
  - `TEAM_MANAGE`
  - `PROPERTY_ARCHIVE_TENANT`
- Regla:
  - si el `PROPERTY_ADMIN` tiene estas permissions, puede actuar en nombre operativo del dueño;
  - si no, solo observa o gestiona su parte limitada.
- `ACCOUNTANT` debe tener visibilidad completa de:
  - ingresos;
  - egresos;
  - pagos parciales a proveedor/agente;
  - saldos pendientes;
  - convenios activos/incumplidos;
  - movimientos del inmueble;
  - disputas de pago;
  - vacancias y su impacto operativo.

### 8. Reportería operativa y contable
El reporte mensual/anual ya no solo informa renta y cobranza.

Debe incluir:
- ingresos esperados/cobrados/pendientes;
- convenios activos, incumplidos y tipo de convenio;
- mantenimiento:
  - presupuestado,
  - aprobado,
  - pagado,
  - pendiente,
  - confirmado por proveedor,
  - disputado;
- comercial/agentes:
  - comisión aprobada,
  - pagada,
  - pendiente,
  - confirmada por agente,
  - disputada;
- inmuebles ocupados, liberados, en vacancia;
- seguimiento comercial activo;
- tareas pendientes relevantes;
- resumen visible para:
  - `OWNER`
  - `PROPERTY_ADMIN`
  - `ACCOUNTANT`
- además un reporte interno de plataforma para saber:
  - qué se pagó completo;
  - qué falta pagar a terceros;
  - qué falta cobrar;
  - qué convenios están abiertos y de qué tipo.

## Cambios en APIs e interfaces
- `POST /api/tenants`
  - alta integral del expediente activo por `propertyId`
- `POST /api/tenants/{tenantProfileId}/archive`
  - archiva expediente, libera inmueble y abre vacancia
- `POST /api/tenants/{tenantProfileId}/contract-document`
- `GET /api/tenants/{tenantProfileId}/contracts`
- `POST /api/vacancies`
  - trabaja por `propertyId`
- `POST /api/vacancies/{id}/route`
  - recibe `source=PRIVATE|PLATFORM`
- `GET /api/owner/team/routing-preferences`
- `PUT /api/owner/team/routing-preferences`
- Nuevos endpoints de settlement de egreso:
  - `POST /api/expenses/{id}/payments`
  - `POST /api/expenses/{id}/confirmations/owner`
  - `POST /api/expenses/{id}/confirmations/provider`
  - `GET /api/expenses/{id}/settlement`
- `OwnerAccountingSummaryDTO` y reportes deben ampliarse con:
  - estado de settlement del egreso;
  - monto pagado;
  - pendiente;
  - confirmación bilateral;
  - disputa;
  - actor/proveedor/agente relacionado.

## Pruebas y escenarios
- Alta de arrendatario:
  - ocupa inmueble automáticamente;
  - rechaza si el inmueble ya tiene expediente activo.
- Baja de arrendatario:
  - archiva expediente;
  - libera inmueble;
  - crea vacancia;
  - crea task + movimiento + evento n8n.
- Contrato:
  - sube PDF y se descarga desde expediente.
- Vacancia:
  - con `PRIVATE` sin agente privado, bloquea y obliga a decidir;
  - con `PLATFORM`, asigna y notifica;
  - agente sube fotos y seguimiento.
- Mantenimiento:
  - respeta preferencia `PRIVATE/PLATFORM`;
  - sin privado no hace fallback silencioso;
  - cotización se aprueba por dueño o admin con permiso;
  - pago puede quedar parcial o total.
- Pago a proveedor/agente:
  - owner/admin registra pago parcial;
  - proveedor/agente confirma parcial;
  - queda saldo pendiente visible;
  - si hay desacuerdo, crea disputa y task.
- Reportes:
  - dueño/admin/contador ven mismos movimientos;
  - muestran qué falta pagar, qué falta cobrar y qué convenios siguen abiertos.

## Suposiciones y defaults
- Se mantiene el nombre técnico `Lease` si reduce riesgo de migración, pero semánticamente representa contrato/tenencia del inmueble.
- Solo puede haber **un expediente activo por inmueble**.
- Crear arrendatario implica ocupación inmediata del inmueble.
- Baja operativa archiva historial; no borra pagos, convenios, contratos ni auditoría.
- La aprobación sensible sigue ocurriendo en la app; n8n solo notifica y acompaña.
- `PROPERTY_ADMIN` solo autoriza presupuestos/pagos si tiene permissions explícitas.
- `ACCOUNTANT` observa y reporta todo, pero no aprueba por default.
