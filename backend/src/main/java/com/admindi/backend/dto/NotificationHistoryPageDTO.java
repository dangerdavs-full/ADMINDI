package com.admindi.backend.dto;

import java.util.List;

/**
 * Respuesta paginada del historial de notificaciones (Bloque C).
 *
 * <p>Incluye contadores agregados para que el frontend pueda renderizar
 * cards de resumen (*Total / Enviadas / Fallidas*) sin recalcular en cliente.
 *
 * <p>Los bounds del calendario son los efectivos del usuario que consulta:
 * se enviarán al frontend para que el selector de mes bloquee navegación
 * fuera del rango válido.
 */
public class NotificationHistoryPageDTO {

    private List<NotificationHistoryEntryDTO> entries;
    private int totalCount;
    private int sentCount;
    private int failedCount;
    private int skippedCount;
    private String monthYear;   // "YYYY-MM" consultado
    private String minMonth;    // límite inferior efectivo (max(ownerCreated, last3months))
    private String maxMonth;    // límite superior efectivo (mes actual)

    public NotificationHistoryPageDTO() {}

    public NotificationHistoryPageDTO(List<NotificationHistoryEntryDTO> entries, int totalCount,
                                      int sentCount, int failedCount, int skippedCount,
                                      String monthYear, String minMonth, String maxMonth) {
        this.entries = entries;
        this.totalCount = totalCount;
        this.sentCount = sentCount;
        this.failedCount = failedCount;
        this.skippedCount = skippedCount;
        this.monthYear = monthYear;
        this.minMonth = minMonth;
        this.maxMonth = maxMonth;
    }

    public List<NotificationHistoryEntryDTO> getEntries() { return entries; }
    public void setEntries(List<NotificationHistoryEntryDTO> entries) { this.entries = entries; }
    public int getTotalCount() { return totalCount; }
    public void setTotalCount(int totalCount) { this.totalCount = totalCount; }
    public int getSentCount() { return sentCount; }
    public void setSentCount(int sentCount) { this.sentCount = sentCount; }
    public int getFailedCount() { return failedCount; }
    public void setFailedCount(int failedCount) { this.failedCount = failedCount; }
    public int getSkippedCount() { return skippedCount; }
    public void setSkippedCount(int skippedCount) { this.skippedCount = skippedCount; }
    public String getMonthYear() { return monthYear; }
    public void setMonthYear(String monthYear) { this.monthYear = monthYear; }
    public String getMinMonth() { return minMonth; }
    public void setMinMonth(String minMonth) { this.minMonth = minMonth; }
    public String getMaxMonth() { return maxMonth; }
    public void setMaxMonth(String maxMonth) { this.maxMonth = maxMonth; }
}
