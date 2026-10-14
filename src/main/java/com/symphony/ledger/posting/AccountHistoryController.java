package com.symphony.ledger.posting;

import com.symphony.ledger.common.Money;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AccountHistoryController {

    private final AccountHistoryService history;

    public AccountHistoryController(AccountHistoryService history) {
        this.history = history;
    }

    /** Oldest first; {@code amount} is signed from this account's point of view (DESIGN.md §8.7). */
    @GetMapping("/accounts/{id}/transactions")
    HistoryResponse history(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return HistoryResponse.from(history.history(id, page, size));
    }

    record HistoryResponse(UUID accountId, String currency, List<Item> items, int page, int size,
            long totalItems, long totalPages) {

        static HistoryResponse from(AccountHistoryService.AccountHistory h) {
            return new HistoryResponse(
                    h.account().id(),
                    h.account().currency().getCurrencyCode(),
                    h.lines().stream().map(Item::from).toList(),
                    h.page(),
                    h.size(),
                    h.totalItems(),
                    (h.totalItems() + h.size() - 1) / h.size());
        }
    }

    record Item(UUID transactionId, long entryId, TransactionType type, String amount, UUID sourceAccountId,
            UUID destinationAccountId, String fxRate, UUID reversesTransactionId, Instant createdAt) {

        static Item from(AccountHistoryService.AccountHistory.Line line) {
            Entry entry = line.entry();
            Transaction tx = line.posting().transaction();
            return new Item(
                    tx.id(),
                    entry.id(),
                    tx.type(),
                    Money.format(entry.amount(), entry.currency()),
                    line.posting().source().accountId(),
                    line.posting().destination().accountId(),
                    tx.fxRate() == null ? null : Money.normalize(tx.fxRate()),
                    tx.reversesTransactionId(),
                    entry.createdAt());
        }
    }
}
