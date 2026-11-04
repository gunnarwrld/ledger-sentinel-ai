package io.paymentslab.payments;

/** Coarse on purpose: customers see these, rule-level detail stays with operators (DESIGN §4.3). */
public enum DeclineReason {
    INSUFFICIENT_FUNDS, ACCOUNT_NOT_ACTIVE, RISK_DECLINED
}
