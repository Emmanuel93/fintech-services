package com.fintech.shared.selfcheck;

import java.math.BigDecimal;

/**
 * One check a service runs on its own data: what it compared, whether it holds, and whether a failure stops whoever
 * asked (a deterministic run's verdict, an operator before a close) or is reported only. The same shape as the
 * accounting close's checks, so one reader reads them all.
 *
 * @param items  how many records are off (or reported, for an informative check)
 * @param amount how much is off, in pesos, when it is a matter of amounts
 * @param detail what was compared, in words, with the figures
 */
public record SelfCheck(String code, String title, boolean ok, boolean blocking, Integer items, BigDecimal amount,
                        String detail) {

    /** A check that must hold: off as soon as one record is off. */
    public static SelfCheck mustHold(String code, String title, int off, BigDecimal amount, String detail) {
        return new SelfCheck(code, title, off == 0, true, off, amount, detail);
    }

    /** A check that informs: what it reports is worth a look, and stops nobody. */
    public static SelfCheck informs(String code, String title, int reported, BigDecimal amount, String detail) {
        return new SelfCheck(code, title, reported == 0, false, reported, amount, detail);
    }
}
