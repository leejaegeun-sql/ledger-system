package com.symphony.ledger.posting;

import com.symphony.ledger.common.Money;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Money-moving endpoints. Each one requires an {@code Idempotency-Key} header (DESIGN.md §7.1). */
@RestController
public class TransactionController {

    private final PostingService postings;

    public TransactionController(PostingService postings) {
        this.postings = postings;
    }

    @PostMapping("/accounts/{id}/deposits")
    ResponseEntity<TransactionResponse> deposit(
            @PathVariable UUID id,
            @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody DepositRequest request) {
        String key = IdempotencyKeys.require(idempotencyKey, "POST /accounts/{id}/deposits");
        Money.requirePositiveWithinMax(request.amount());
        Posting posting = postings.deposit(id, request.amount(), key, RequestHasher.deposit(id, request.amount()));
        return created(posting);
    }

    private static ResponseEntity<TransactionResponse> created(Posting posting) {
        // reversedByTransactionId is always null on a newly created transaction.
        return ResponseEntity.created(URI.create("/transactions/" + posting.transaction().id()))
                .body(TransactionResponse.from(posting, null));
    }

    /** {@code amount} is a JSON string (preferred) or number, parsed straight to BigDecimal. */
    record DepositRequest(@NotNull BigDecimal amount) {
    }
}
