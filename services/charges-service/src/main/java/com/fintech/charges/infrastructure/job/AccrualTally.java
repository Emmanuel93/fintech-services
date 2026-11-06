package com.fintech.charges.infrastructure.job;

/** Lo que dejó una corrida de devengo: cuántos calendarios tocaba, cuántos se devengaron, cuántos fallaron. */
public record AccrualTally(int eligible, int success, int errors) {}
