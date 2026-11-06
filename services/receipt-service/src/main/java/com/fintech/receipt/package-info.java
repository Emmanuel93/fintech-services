/**
 * receipt-service: the payment receipt of each client of a distributor, per statement.
 *
 * <p>It consumes {@code closing.cutoff-closed} in its own consumer group, in parallel with statement-service and
 * without depending on it: a product that pays no distributor has statements and no receipts.
 */
package com.fintech.receipt;
