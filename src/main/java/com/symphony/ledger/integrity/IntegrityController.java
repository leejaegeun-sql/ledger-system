package com.symphony.ledger.integrity;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class IntegrityController {

    private final IntegrityService integrity;

    public IntegrityController(IntegrityService integrity) {
        this.integrity = integrity;
    }

    /** 200 whether or not discrepancies are found: the check ran, and {@code status} says what it found (§8.11). */
    @GetMapping("/ledger/integrity")
    IntegrityReport check() {
        return integrity.check();
    }
}
