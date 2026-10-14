package com.symphony.ledger.account;

import com.symphony.ledger.common.Money;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final AccountService accounts;

    public AccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    @PostMapping
    ResponseEntity<AccountResponse> create(@Valid @RequestBody CreateAccountRequest request) {
        Account account = accounts.createCustomer(request.name(), Currency.getInstance(request.currency()));
        return ResponseEntity.created(URI.create("/accounts/" + account.id())).body(AccountResponse.from(account));
    }

    @GetMapping("/{id}")
    AccountResponse get(@PathVariable UUID id) {
        return AccountResponse.from(accounts.get(id));
    }

    /** A {@code type} field is ignored: only customer accounts can be created. */
    record CreateAccountRequest(
            @NotBlank @Size(max = 200) String name,
            @NotNull @Pattern(regexp = "SGD|USD|EUR|JPY", message = "must be one of SGD, USD, EUR, JPY") String currency) {
    }

    record AccountResponse(UUID id, String name, String currency, AccountType type, String balance, Instant createdAt) {

        static AccountResponse from(Account a) {
            return new AccountResponse(a.id(), a.name(), a.currency().getCurrencyCode(), a.type(),
                    Money.format(a.balance(), a.currency()), a.createdAt());
        }
    }
}
