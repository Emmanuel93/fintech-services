package com.fintech.creditproduct.domain;

/**
 * How often a credit pays.
 *
 * <p>{@code BIWEEKLY} is every fourteen days (26 a year) counted from a start date. {@code SEMI_MONTHLY} is the calendar
 * fortnight: two per month, ending on the 15th and on the month's last day (24 a year). It is the one a distributor
 * line lives by.
 */
public enum PaymentFrequency {
    WEEKLY,
    BIWEEKLY,
    SEMI_MONTHLY,
    MONTHLY
}
