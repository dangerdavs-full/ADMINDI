package com.admindi.backend.service;

import com.admindi.backend.model.ExpenseEntity;
import com.admindi.backend.model.InvoiceEntity;
import com.admindi.backend.model.PaymentAgreementEntity;
import com.admindi.backend.model.PaymentEntity;
import com.admindi.backend.model.TenantProfileEntity;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.model.AgreementInstallmentEntity;
import com.admindi.backend.model.InstallmentStatus;
import com.admindi.backend.repository.ExpenseRepository;
import com.admindi.backend.repository.InvoiceRepository;
import com.admindi.backend.repository.PaymentAgreementRepository;
import com.admindi.backend.repository.AgreementInstallmentRepository;
import com.admindi.backend.repository.PaymentRepository;
import com.admindi.backend.repository.TenantProfileRepository;
import com.admindi.backend.repository.UserRepository;
import com.admindi.backend.security.TenantContext;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class ReportService {

    private final InvoiceRepository invoiceRepository;
    private final TenantProfileRepository profileRepository;
    private final UserRepository userRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentAgreementRepository agreementRepository;
    private final AgreementInstallmentRepository installmentRepository;
    private final ExpenseRepository expenseRepository;
    private final ReportingPeriodService reportingPeriodService;

    @Autowired
    public ReportService(InvoiceRepository invoiceRepository, TenantProfileRepository profileRepository,
                         UserRepository userRepository, PaymentRepository paymentRepository,
                         PaymentAgreementRepository agreementRepository,
                         AgreementInstallmentRepository installmentRepository,
                         ExpenseRepository expenseRepository,
                         ReportingPeriodService reportingPeriodService) {
        this.invoiceRepository = invoiceRepository;
        this.profileRepository = profileRepository;
        this.userRepository = userRepository;
        this.paymentRepository = paymentRepository;
        this.agreementRepository = agreementRepository;
        this.installmentRepository = installmentRepository;
        this.expenseRepository = expenseRepository;
        this.reportingPeriodService = reportingPeriodService;
    }

    private String resolveOwnerId() {
        return TenantContext.resolveOwnerId(userRepository);
    }

    // ─── Helpers ────────────────────────────────────────────────────────

    private record TenantInfo(String name, String email) {}

    private TenantInfo resolveTenant(String tenantProfileId) {
        String name = "Desconocido"; String email = "N/A";
        Optional<TenantProfileEntity> profile = profileRepository.findById(tenantProfileId);
        if (profile.isPresent()) {
            Optional<UserEntity> user = userRepository.findById(profile.get().getUserId());
            if (user.isPresent()) { name = user.get().getName(); email = user.get().getContactEmail(); }
        }
        return new TenantInfo(name, email);
    }

    private List<InvoiceEntity> getInvoicesForMonth(String monthYear) {
        String orgId = resolveOwnerId();
        return invoiceRepository.findByOwnerId(orgId).stream()
                .filter(inv -> inv.getMonthYear().equals(monthYear))
                .collect(Collectors.toList());
    }

    private BigDecimal safe(BigDecimal v) { return v != null ? v : BigDecimal.ZERO; }

    // ─── ZIP (with settlement columns) ──────────────────────────────────

    public byte[] generateMonthlyAccountantZip(String monthYear) throws Exception {
        reportingPeriodService.validateOwnerMonthYear(monthYear);
        List<InvoiceEntity> invoices = getInvoicesForMonth(monthYear);
        String orgId = resolveOwnerId();
        YearMonth ym = YearMonth.parse(monthYear);
        LocalDate monthStart = ym.atDay(1);
        LocalDate monthEnd = ym.atEndOfMonth();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            StringBuilder csv = new StringBuilder();
            csv.append("ID Factura,Inquilino,Email,Estatus,Monto Base,Recargos,Total,Pagado,Pendiente,Saldo a Favor,Liquidación,Fecha Pagado,Referencias/Notas,RazonParcial,FechaCompromiso\n");

            for (InvoiceEntity inv : invoices) {
                TenantInfo t = resolveTenant(inv.getTenantProfileId());
                csv.append(inv.getId()).append(",")
                   .append(t.name().replace(",", " ")).append(",")
                   .append(t.email()).append(",")
                   .append(inv.getStatus()).append(",")
                   .append(inv.getBaseAmount()).append(",")
                   .append(safe(inv.getAppliedLateFee())).append(",")
                   .append(inv.getTotalAmount()).append(",")
                   .append(safe(inv.getPaidAmount())).append(",")
                   .append(safe(inv.getOutstandingAmount())).append(",")
                   .append(safe(inv.getCreditBalance())).append(",")
                   .append(inv.getSettlementStatus() != null ? inv.getSettlementStatus() : "UNPAID").append(",")
                   .append(inv.getPaidDate() != null ? inv.getPaidDate() : "NO PAGADO").append(",")
                   .append(inv.getPaymentReference() != null ? inv.getPaymentReference().replace(",", " ") : "N/A").append(",")
                   .append(inv.getShortfallReason() != null ? inv.getShortfallReason() : "").append(",")
                   .append(inv.getPromisedCompletionDate() != null ? inv.getPromisedCompletionDate() : "")
                   .append("\n");

                if (inv.getProofOfPaymentUrl() != null) {
                    try {
                        String localPathStr = inv.getProofOfPaymentUrl().startsWith("/") ? inv.getProofOfPaymentUrl().substring(1) : inv.getProofOfPaymentUrl();
                        Path filePath = Paths.get(localPathStr);
                        if (Files.exists(filePath)) {
                            ZipEntry entry = new ZipEntry("comprobantes/" + t.name().replace(" ", "_") + "_" + filePath.getFileName().toString());
                            zos.putNextEntry(entry);
                            Files.copy(filePath, zos);
                            zos.closeEntry();
                        }
                    } catch (Exception ignored) {}
                }
            }

            ZipEntry csvEntry = new ZipEntry("BalanceGeneral_" + monthYear + ".csv");
            zos.putNextEntry(csvEntry);
            zos.write(csv.toString().getBytes());
            zos.closeEntry();

            // Convenios (mes de la factura vinculada)
            StringBuilder agCsv = new StringBuilder();
            agCsv.append("ID,Inquilino,Email,MesFactura,Estatus,Solicitado,Aprobado,Diferido,Cuotas,Pagadas,Vencidas\n");
            for (PaymentAgreementEntity ag : agreementRepository.findByOwnerId(orgId)) {
                if (ag.getInvoiceId() == null) continue;
                InvoiceEntity agInv = invoiceRepository.findById(ag.getInvoiceId()).orElse(null);
                if (agInv == null || !agInv.getMonthYear().equals(monthYear)) continue;
                TenantInfo t = resolveTenant(ag.getTenantProfileId());
                List<AgreementInstallmentEntity> installments = installmentRepository.findByAgreementId(ag.getId());
                long paidCount = installments.stream().filter(inst -> inst.getStatus() == InstallmentStatus.PAID).count();
                long lateCount = installments.stream().filter(inst -> inst.getStatus() == InstallmentStatus.LATE).count();
                String agMonth = "N/A";
                if (ag.getInvoiceId() != null) {
                    InvoiceEntity agInv2 = invoiceRepository.findById(ag.getInvoiceId()).orElse(null);
                    if (agInv2 != null) agMonth = agInv2.getMonthYear();
                }
                agCsv.append(ag.getId()).append(",")
                   .append(t.name().replace(",", " ")).append(",")
                   .append(t.email()).append(",")
                   .append(agMonth).append(",")
                   .append(ag.getStatus().name()).append(",")
                   .append(ag.getRequestedAmount()).append(",")
                   .append(safe(ag.getApprovedAmount())).append(",")
                   .append(safe(ag.getDeferredAmount())).append(",")
                   .append(installments.size()).append(",")
                   .append(paidCount).append(",")
                   .append(lateCount).append("\n");
            }
            ZipEntry agEntry = new ZipEntry("Convenios_" + monthYear + ".csv");
            zos.putNextEntry(agEntry);
            zos.write(agCsv.toString().getBytes());
            zos.closeEntry();

            // Egresos con actividad en el mes (creado o pagado dentro del rango)
            // V64 — incluimos columnas "Neto" (lo que salió del bolsillo) y
            // "CreditoPlataforma" (descuento 15%). El reporte histórico sigue
            // mostrando "Monto" = bruto para back-compat, pero el valor real
            // del gasto es la columna Neto.
            StringBuilder exCsv = new StringBuilder();
            exCsv.append("ID,PropertyId,Tipo,Monto,CreditoPlataforma,Neto,Estatus,Creado,PaidAt,Descripcion\n");
            for (ExpenseEntity e : expenseRepository.findByOwnerId(orgId)) {
                LocalDate created = e.getCreatedAt() != null ? e.getCreatedAt().toLocalDate() : null;
                LocalDate paid = e.getPaidAt() != null ? e.getPaidAt().toLocalDate() : null;
                boolean inMonth = (paid != null && !paid.isBefore(monthStart) && !paid.isAfter(monthEnd))
                        || (paid == null && created != null && !created.isBefore(monthStart) && !created.isAfter(monthEnd));
                if (!inMonth) continue;
                java.math.BigDecimal credit = e.getPlatformCreditAmount() != null
                        ? e.getPlatformCreditAmount() : java.math.BigDecimal.ZERO;
                java.math.BigDecimal net = e.getNetExpenseAmount() != null
                        ? e.getNetExpenseAmount()
                        : (e.getAmount() != null ? e.getAmount().subtract(credit) : java.math.BigDecimal.ZERO);
                exCsv.append(e.getId()).append(",")
                   .append(e.getPropertyId()).append(",")
                   .append(e.getType()).append(",")
                   .append(e.getAmount()).append(",")
                   .append(credit).append(",")
                   .append(net).append(",")
                   .append(e.getStatus()).append(",")
                   .append(created != null ? created : "").append(",")
                   .append(paid != null ? paid : "").append(",")
                   .append(e.getDescription() != null ? e.getDescription().replace(",", " ") : "")
                   .append("\n");
            }
            ZipEntry exEntry = new ZipEntry("Egresos_" + monthYear + ".csv");
            zos.putNextEntry(exEntry);
            zos.write(exCsv.toString().getBytes());
            zos.closeEntry();
        }
        return baos.toByteArray();
    }

    // ─── Excel (.xlsx) with settlement accounting ───────────────────────

    public byte[] generateMonthlyExcel(String monthYear) throws Exception {
        reportingPeriodService.validateOwnerMonthYear(monthYear);
        List<InvoiceEntity> invoices = getInvoicesForMonth(monthYear);
        String orgId = resolveOwnerId();
        List<PaymentEntity> payments = paymentRepository.findByOwnerId(orgId);

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            // --- Styles ---
            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true); headerFont.setFontHeightInPoints((short) 11);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            CellStyle moneyStyle = workbook.createCellStyle();
            moneyStyle.setDataFormat(workbook.createDataFormat().getFormat("$#,##0.00"));

            // ─── Sheet 1: Resumen (with settlement) ───
            Sheet summary = workbook.createSheet("Resumen");
            String[] summaryHeaders = {"Mes", "Inquilino", "Email", "Estatus", "Base", "Recargo", "Total", "Pagado", "Pendiente", "Saldo a Favor", "Liquidación", "Fecha Pago", "Razon Parcial", "Fecha Compromiso", "Convenio"};
            Row sHdr = summary.createRow(0);
            for (int i = 0; i < summaryHeaders.length; i++) { Cell c = sHdr.createCell(i); c.setCellValue(summaryHeaders[i]); c.setCellStyle(headerStyle); }

            int sRow = 1;
            BigDecimal totalBase = BigDecimal.ZERO, totalFee = BigDecimal.ZERO, totalAll = BigDecimal.ZERO;
            BigDecimal totalPaid = BigDecimal.ZERO, totalOutstanding = BigDecimal.ZERO, totalCredit = BigDecimal.ZERO;

            for (InvoiceEntity inv : invoices) {
                TenantInfo t = resolveTenant(inv.getTenantProfileId());
                Row row = summary.createRow(sRow++);
                row.createCell(0).setCellValue(inv.getMonthYear());
                row.createCell(1).setCellValue(t.name());
                row.createCell(2).setCellValue(t.email());
                row.createCell(3).setCellValue(inv.getStatus());
                Cell baseCell = row.createCell(4); baseCell.setCellValue(inv.getBaseAmount().doubleValue()); baseCell.setCellStyle(moneyStyle);
                Cell feeCell = row.createCell(5); feeCell.setCellValue(safe(inv.getAppliedLateFee()).doubleValue()); feeCell.setCellStyle(moneyStyle);
                Cell totalCell = row.createCell(6); totalCell.setCellValue(inv.getTotalAmount().doubleValue()); totalCell.setCellStyle(moneyStyle);
                Cell paidCell = row.createCell(7); paidCell.setCellValue(safe(inv.getPaidAmount()).doubleValue()); paidCell.setCellStyle(moneyStyle);
                Cell outCell = row.createCell(8); outCell.setCellValue(safe(inv.getOutstandingAmount()).doubleValue()); outCell.setCellStyle(moneyStyle);
                Cell creditCell = row.createCell(9); creditCell.setCellValue(safe(inv.getCreditBalance()).doubleValue()); creditCell.setCellStyle(moneyStyle);
                row.createCell(10).setCellValue(inv.getSettlementStatus() != null ? inv.getSettlementStatus() : "UNPAID");
                row.createCell(11).setCellValue(inv.getPaidDate() != null ? inv.getPaidDate().toString() : "");
                row.createCell(12).setCellValue(inv.getShortfallReason() != null ? inv.getShortfallReason() : "");
                row.createCell(13).setCellValue(inv.getPromisedCompletionDate() != null ? inv.getPromisedCompletionDate().toString() : "");
                String agSum = "";
                Optional<PaymentAgreementEntity> agOpt = agreementRepository.findByInvoiceId(inv.getId()).stream().findFirst();
                if (agOpt.isPresent()) agSum = agOpt.get().getStatus().name();
                row.createCell(14).setCellValue(agSum);

                totalBase = totalBase.add(inv.getBaseAmount());
                totalFee = totalFee.add(safe(inv.getAppliedLateFee()));
                totalAll = totalAll.add(inv.getTotalAmount());
                totalPaid = totalPaid.add(safe(inv.getPaidAmount()));
                totalOutstanding = totalOutstanding.add(safe(inv.getOutstandingAmount()));
                totalCredit = totalCredit.add(safe(inv.getCreditBalance()));
            }

            // Totals row
            Row totalsRow = summary.createRow(sRow);
            totalsRow.createCell(0).setCellValue("TOTALES");
            totalsRow.getCell(0).setCellStyle(headerStyle);
            Cell tb = totalsRow.createCell(4); tb.setCellValue(totalBase.doubleValue()); tb.setCellStyle(moneyStyle);
            Cell tf = totalsRow.createCell(5); tf.setCellValue(totalFee.doubleValue()); tf.setCellStyle(moneyStyle);
            Cell ta = totalsRow.createCell(6); ta.setCellValue(totalAll.doubleValue()); ta.setCellStyle(moneyStyle);
            Cell tp = totalsRow.createCell(7); tp.setCellValue(totalPaid.doubleValue()); tp.setCellStyle(moneyStyle);
            Cell to = totalsRow.createCell(8); to.setCellValue(totalOutstanding.doubleValue()); to.setCellStyle(moneyStyle);
            Cell tc = totalsRow.createCell(9); tc.setCellValue(totalCredit.doubleValue()); tc.setCellStyle(moneyStyle);

            for (int i = 0; i < summaryHeaders.length; i++) summary.autoSizeColumn(i);

            // ─── Sheet 2: Pagos (with applied/unapplied) ───
            Sheet paySheet = workbook.createSheet("Pagos");
            String[] payHeaders = {"ID", "Inquilino", "Monto", "Aplicado", "No Aplicado", "Método", "Referencia", "Estatus", "Fecha Pago", "Confirmado Por", "Notas"};
            Row pHdr = paySheet.createRow(0);
            for (int i = 0; i < payHeaders.length; i++) { Cell c = pHdr.createCell(i); c.setCellValue(payHeaders[i]); c.setCellStyle(headerStyle); }

            int pRow = 1;
            for (PaymentEntity pay : payments) {
                InvoiceEntity inv = invoiceRepository.findById(pay.getInvoiceId()).orElse(null);
                if (inv != null && !inv.getMonthYear().equals(monthYear)) continue;

                TenantInfo t = inv != null ? resolveTenant(inv.getTenantProfileId()) : new TenantInfo("-", "-");
                Row row = paySheet.createRow(pRow++);
                row.createCell(0).setCellValue(pay.getId().substring(0, 8));
                row.createCell(1).setCellValue(t.name());
                Cell amt = row.createCell(2); amt.setCellValue(pay.getAmount().doubleValue()); amt.setCellStyle(moneyStyle);
                Cell applied = row.createCell(3); applied.setCellValue(safe(pay.getAppliedAmount()).doubleValue()); applied.setCellStyle(moneyStyle);
                Cell unapplied = row.createCell(4); unapplied.setCellValue(safe(pay.getUnappliedAmount()).doubleValue()); unapplied.setCellStyle(moneyStyle);
                row.createCell(5).setCellValue(pay.getPaymentMethod().name());
                row.createCell(6).setCellValue(pay.getGatewayReference() != null ? pay.getGatewayReference() : "");
                row.createCell(7).setCellValue(pay.getStatus().name());
                row.createCell(8).setCellValue(pay.getPaidAt() != null ? pay.getPaidAt().toString() : "");
                row.createCell(9).setCellValue(pay.getConfirmedBy() != null ? pay.getConfirmedBy() : "");
                row.createCell(10).setCellValue(pay.getNotes() != null ? pay.getNotes() : "");
            }
            for (int i = 0; i < payHeaders.length; i++) paySheet.autoSizeColumn(i);

            // ─── Sheet 3: Morosidad (uses outstandingAmount) ───
            Sheet lateSheet = workbook.createSheet("Morosidad");
            String[] lateHeaders = {"Inquilino", "Email", "Mes", "Monto Vencido", "Pendiente Real", "Recargo", "Días de Retraso"};
            Row lHdr = lateSheet.createRow(0);
            for (int i = 0; i < lateHeaders.length; i++) { Cell c = lHdr.createCell(i); c.setCellValue(lateHeaders[i]); c.setCellStyle(headerStyle); }

            int lRow = 1;
            for (InvoiceEntity inv : invoices) {
                if (!"LATE".equals(inv.getStatus()) && !"PARTIALLY_PAID".equals(inv.getStatus())) continue;
                TenantInfo t = resolveTenant(inv.getTenantProfileId());
                Row row = lateSheet.createRow(lRow++);
                row.createCell(0).setCellValue(t.name());
                row.createCell(1).setCellValue(t.email());
                row.createCell(2).setCellValue(inv.getMonthYear());
                Cell vencido = row.createCell(3); vencido.setCellValue(inv.getTotalAmount().doubleValue()); vencido.setCellStyle(moneyStyle);
                Cell pendiente = row.createCell(4); pendiente.setCellValue(safe(inv.getOutstandingAmount()).doubleValue()); pendiente.setCellStyle(moneyStyle);
                Cell recargo = row.createCell(5); recargo.setCellValue(safe(inv.getAppliedLateFee()).doubleValue()); recargo.setCellStyle(moneyStyle);
                long days = java.time.temporal.ChronoUnit.DAYS.between(inv.getDueDate(), java.time.LocalDate.now());
                row.createCell(6).setCellValue(days > 0 ? days : 0);
            }
            for (int i = 0; i < lateHeaders.length; i++) lateSheet.autoSizeColumn(i);

            // ─── Sheet 4: Convenios ───
            Sheet agSheet = workbook.createSheet("Convenios");
            String[] agHeaders = {"Inquilino", "Email", "Mes", "Estatus", "Solicitado", "Aprobado", "Diferido", "Parcialidades", "Pagadas", "Vencidas", "Aprobado Por", "Fecha Aprobación"};
            Row agHdr = agSheet.createRow(0);
            for (int i = 0; i < agHeaders.length; i++) { Cell c = agHdr.createCell(i); c.setCellValue(agHeaders[i]); c.setCellStyle(headerStyle); }

            List<PaymentAgreementEntity> agreements = agreementRepository.findByOwnerId(orgId);
            int agRow = 1;
            for (PaymentAgreementEntity ag : agreements) {
                TenantInfo t = resolveTenant(ag.getTenantProfileId());

                // Optionally filter by month if invoice is present
                if (ag.getInvoiceId() != null) {
                    InvoiceEntity agInv = invoiceRepository.findById(ag.getInvoiceId()).orElse(null);
                    if (agInv != null && !agInv.getMonthYear().equals(monthYear)) continue;
                }

                List<AgreementInstallmentEntity> installments = installmentRepository.findByAgreementId(ag.getId());
                long paidCount = installments.stream().filter(inst -> inst.getStatus() == InstallmentStatus.PAID).count();
                long lateCount = installments.stream().filter(inst -> inst.getStatus() == InstallmentStatus.LATE).count();

                Row row = agSheet.createRow(agRow++);
                row.createCell(0).setCellValue(t.name());
                row.createCell(1).setCellValue(t.email());
                // Month from invoice
                String agMonth = "N/A";
                if (ag.getInvoiceId() != null) {
                    InvoiceEntity agInv2 = invoiceRepository.findById(ag.getInvoiceId()).orElse(null);
                    if (agInv2 != null) agMonth = agInv2.getMonthYear();
                }
                row.createCell(2).setCellValue(agMonth);
                row.createCell(3).setCellValue(ag.getStatus().name());
                Cell reqCell = row.createCell(4); reqCell.setCellValue(ag.getRequestedAmount().doubleValue()); reqCell.setCellStyle(moneyStyle);
                Cell appCell = row.createCell(5); appCell.setCellValue(safe(ag.getApprovedAmount()).doubleValue()); appCell.setCellStyle(moneyStyle);
                Cell defCell = row.createCell(6); defCell.setCellValue(safe(ag.getDeferredAmount()).doubleValue()); defCell.setCellStyle(moneyStyle);
                row.createCell(7).setCellValue(installments.size());
                row.createCell(8).setCellValue(paidCount);
                row.createCell(9).setCellValue(lateCount);
                row.createCell(10).setCellValue(ag.getApprovedBy() != null ? ag.getApprovedBy() : "");
                row.createCell(11).setCellValue(ag.getApprovedAt() != null ? ag.getApprovedAt().toString() : "");
            }
            for (int i = 0; i < agHeaders.length; i++) agSheet.autoSizeColumn(i);

            // Write
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        }
    }
}
