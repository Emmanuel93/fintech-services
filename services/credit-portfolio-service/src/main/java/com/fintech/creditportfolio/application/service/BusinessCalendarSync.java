package com.fintech.creditportfolio.application.service;

import com.fintech.creditportfolio.application.port.out.CreditAccountRepository;
import com.fintech.creditportfolio.application.port.out.InstallmentRepository;
import com.fintech.creditportfolio.domain.Installment;
import com.fintech.shared.time.BusinessClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * Keeps the portfolio's copy of the business calendar and its consequences. A day that becomes a holiday after
 * loans were dated (the calendar of next year arrives in December, a day is decreed) moves the installments of loans
 * still to fall due that day to the next business day. Installments already past are left as they are: what happened
 * on them happened. Statement-billed plans (placements, purchases at months) are due on their statement's date, which
 * closing already moves.
 */
@Service
public class BusinessCalendarSync {

    private static final Logger log = LoggerFactory.getLogger(BusinessCalendarSync.class);

    private final BankingCalendar calendar;
    private final InstallmentRepository installments;
    private final CreditAccountRepository accounts;

    public BusinessCalendarSync(BankingCalendar calendar, InstallmentRepository installments,
                                CreditAccountRepository accounts) {
        this.calendar = calendar;
        this.installments = installments;
        this.accounts = accounts;
    }

    @Transactional
    public int onDay(String calendarCode, LocalDate day, boolean business, String label) {
        calendar.record(calendarCode, day, business, label);
        if (business || !BankingCalendar.MX.equals(calendarCode) || day.isBefore(BusinessClock.current().today())) {
            return 0;
        }
        List<Installment> due = installments.findDueOn(day).stream()
                .filter(i -> i.getBilledCycle() == null)
                .filter(i -> accounts.findById(i.getScheduleId()).map(a -> !a.isRevolving()).orElse(false))
                .toList();
        int moved = calendar.followBusinessDays(due);
        if (moved > 0) {
            installments.saveAll(due);
            log.info("Día inhábil {} ({}): {} cuotas de préstamos corridas al {}", day, label, moved, calendar.onOrAfter(day));
        }
        return moved;
    }
}
