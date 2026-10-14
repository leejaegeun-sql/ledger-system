package com.symphony.ledger.posting;

import com.symphony.ledger.common.LedgerException;
import com.symphony.ledger.common.Money;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Money-moving endpoints, each requiring an {@code Idempotency-Key} header (DESIGN.md §7.1), and
 * {@code GET /transactions/{id}}.
 */
@RestController
public class TransactionController {

    static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final PostingService postings;
    private final IdempotentExecutor idempotently;
    private final TransactionLookup lookup;

    public TransactionController(PostingService postings, IdempotentExecutor idempotently, TransactionLookup lookup) {
        this.postings = postings;
        this.idempotently = idempotently;
        this.lookup = lookup;
    }

    @PostMapping("/accounts/{id}/deposits")
    ResponseEntity<TransactionResponse> deposit(
            @PathVariable UUID id,
            @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody DepositRequest request) {
        String key = IdempotencyKeys.require(idempotencyKey, "POST /accounts/{id}/deposits");
        Money.requirePositiveWithinMax(request.amount());
        String hash = RequestHasher.deposit(id, request.amount());
        return created(idempotently.execute(key, hash, () -> postings.deposit(id, request.amount(), key, hash)));
    }

    /** The amount is in the source currency (DESIGN.md §8.5). */
    @PostMapping("/transactions")
    ResponseEntity<TransactionResponse> transfer(
            @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {
        String key = IdempotencyKeys.require(idempotencyKey, "POST /transactions");
        Money.requirePositiveWithinMax(request.amount());
        if (request.sourceAccountId().equals(request.destinationAccountId())) {
            throw LedgerException.sameAccount(request.sourceAccountId());
        }
        String hash = RequestHasher.transfer(request.sourceAccountId(), request.destinationAccountId(),
                request.amount());
        return created(idempotently.execute(key, hash, () -> postings.transfer(request.sourceAccountId(),
                request.destinationAccountId(), request.amount(), key, hash)));
    }

    /** The current state: {@code reversedByTransactionId} is filled in once a reversal exists (DESIGN.md §8.6). */
    @GetMapping("/transactions/{id}")
    TransactionResponse get(@PathVariable UUID id) {
        TransactionLookup.CurrentTransaction current = lookup.get(id);
        return TransactionResponse.from(current.posting(), current.reversedByTransactionId());
    }

    /**
     * A replay returns the original 201, Location and body, plus {@code Idempotent-Replayed: true}. The body
     * shows the transaction as it was created, so {@code reversedByTransactionId} is always null (§7.4).
     */
    private static ResponseEntity<TransactionResponse> created(IdempotentExecutor.Outcome outcome) {
        Posting posting = outcome.posting();
        var response = ResponseEntity.created(URI.create("/transactions/" + posting.transaction().id()));
        if (outcome.replayed()) {
            response.header(REPLAYED_HEADER, "true");
        }
        return response.body(TransactionResponse.from(posting, null));
    }

    /** {@code amount} is a JSON string (preferred) or number, parsed straight to BigDecimal. */
    record DepositRequest(@NotNull BigDecimal amount) {
    }

    /** {@code amount} is a JSON string (preferred) or number, parsed straight to BigDecimal. */
    record TransferRequest(@NotNull UUID sourceAccountId, @NotNull UUID destinationAccountId,
            @NotNull BigDecimal amount) {
    }
}
