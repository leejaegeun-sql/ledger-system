package com.symphony.ledger.posting;

import java.util.List;

/** A committed transaction and its entries, in entry-id order. */
public record Posting(Transaction transaction, List<Entry> entries) {
}
