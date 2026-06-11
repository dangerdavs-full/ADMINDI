package com.admindi.backend.dto;

public class ReportingPeriodBoundsDTO {
    private String minMonthYear;
    private String maxMonthYear;
    private Integer minYear;
    private Integer maxYear;

    public ReportingPeriodBoundsDTO() {}

    public ReportingPeriodBoundsDTO(String minMonthYear, String maxMonthYear, Integer minYear, Integer maxYear) {
        this.minMonthYear = minMonthYear;
        this.maxMonthYear = maxMonthYear;
        this.minYear = minYear;
        this.maxYear = maxYear;
    }

    public String getMinMonthYear() { return minMonthYear; }
    public void setMinMonthYear(String minMonthYear) { this.minMonthYear = minMonthYear; }
    public String getMaxMonthYear() { return maxMonthYear; }
    public void setMaxMonthYear(String maxMonthYear) { this.maxMonthYear = maxMonthYear; }
    public Integer getMinYear() { return minYear; }
    public void setMinYear(Integer minYear) { this.minYear = minYear; }
    public Integer getMaxYear() { return maxYear; }
    public void setMaxYear(Integer maxYear) { this.maxYear = maxYear; }
}