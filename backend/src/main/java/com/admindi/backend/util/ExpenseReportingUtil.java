package com.admindi.backend.util;

import com.admindi.backend.model.ExpenseEntity;

import java.time.YearMonth;

public final class ExpenseReportingUtil {

    private ExpenseReportingUtil() {}

    public static boolean expenseTouchesReportingMonth(ExpenseEntity e, YearMonth ym) {
        if (ym == null || e == null) {
            return false;
        }
        if (e.getCreatedAt() != null && YearMonth.from(e.getCreatedAt()).equals(ym)) {
            return true;
        }
        if (e.getPaidAt() != null && YearMonth.from(e.getPaidAt()).equals(ym)) {
            return true;
        }
        if (e.getApprovedAt() != null && YearMonth.from(e.getApprovedAt()).equals(ym)) {
            return true;
        }
        return false;
    }

    public static boolean expenseTouchesYear(ExpenseEntity e, int year) {
        if (e == null) {
            return false;
        }
        if (e.getCreatedAt() != null && e.getCreatedAt().getYear() == year) {
            return true;
        }
        if (e.getPaidAt() != null && e.getPaidAt().getYear() == year) {
            return true;
        }
        if (e.getApprovedAt() != null && e.getApprovedAt().getYear() == year) {
            return true;
        }
        return false;
    }
}