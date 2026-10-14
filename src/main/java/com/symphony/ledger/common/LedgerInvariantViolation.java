package com.symphony.ledger.common;

import org.springframework.http.HttpStatus;

/** A pre-commit assertion failed (DESIGN.md §5.6). Rolls the posting back; logged at ERROR. */
public class LedgerInvariantViolation extends LedgerException {

    public LedgerInvariantViolation(String detail) {
        super(HttpStatus.INTERNAL_SERVER_ERROR, "ledger-invariant-violation", "Ledger invariant violation", detail);
    }
}
