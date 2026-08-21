package com.fintech.creditportfolio.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "fintech.credit-portfolio")
public class CreditPortfolioProperties {

    /** T5 lead time for UpcomingInstallmentJob — days before dueDate that InstallmentUpcoming fires. */
    private int reminderLeadDays = 3;

    public int getReminderLeadDays()       { return reminderLeadDays; }
    public void setReminderLeadDays(int v) { this.reminderLeadDays = v; }
}
