package com.symphony.ledger.account;

import com.symphony.ledger.common.ClockConfig;
import com.symphony.ledger.common.LedgerException;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Currency;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AccountService {

    private final AccountRepository accounts;
    private final Clock clock;

    public AccountService(AccountRepository accounts, Clock clock) {
        this.accounts = accounts;
        this.clock = clock;
    }

    /** The API can only create customer accounts; they start at zero. */
    public Account createCustomer(String name, Currency currency) {
        Account account = new Account(UUID.randomUUID(), name, currency, AccountType.CUSTOMER, BigDecimal.ZERO,
                ClockConfig.now(clock));
        accounts.insert(account);
        return account;
    }

    public Account get(UUID id) {
        return accounts.findById(id).orElseThrow(() -> LedgerException.accountNotFound(id));
    }
}
