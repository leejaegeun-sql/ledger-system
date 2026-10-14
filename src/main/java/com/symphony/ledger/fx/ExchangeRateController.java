package com.symphony.ledger.fx;

import com.symphony.ledger.common.LedgerException;
import com.symphony.ledger.common.Money;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Sets and reads current rates. Moves no money, so it takes no Idempotency-Key (DESIGN.md §8.9, §8.10). */
@RestController
@RequestMapping("/exchange-rates")
public class ExchangeRateController {

    /** The scale of {@code exchange_rates.rate}, NUMERIC(20,10). */
    static final int MAX_RATE_DECIMALS = 10;

    private final ExchangeRateService rates;

    public ExchangeRateController(ExchangeRateService rates) {
        this.rates = rates;
    }

    @PutMapping
    RatesResponse put(@Valid @RequestBody PutRatesRequest request) {
        List<ExchangeRateService.NewRate> newRates = new ArrayList<>();
        Set<String> pairs = new HashSet<>();
        for (int i = 0; i < request.rates().size(); i++) {
            RateRequest item = request.rates().get(i);
            String field = "rates[" + i + "]";
            if (item.base().equals(item.quote())) {
                throw LedgerException.validationError(field + ".quote", "must differ from base");
            }
            // Trailing zeros don't count, as for amounts (DESIGN.md §3.4).
            if (item.rate().stripTrailingZeros().scale() > MAX_RATE_DECIMALS) {
                throw LedgerException.validationError(field + ".rate",
                        "must have at most " + MAX_RATE_DECIMALS + " decimal places");
            }
            if (!pairs.add(item.base() + "/" + item.quote())) {
                throw LedgerException.validationError(field, "duplicate pair " + item.base() + "/" + item.quote());
            }
            newRates.add(new ExchangeRateService.NewRate(
                    Currency.getInstance(item.base()), Currency.getInstance(item.quote()), item.rate()));
        }
        return new RatesResponse(rates.setRates(newRates).stream().map(RateResponse::from).toList());
    }

    @GetMapping
    CurrentRatesResponse get() {
        ExchangeRateService.CurrentRates current = rates.current();
        return new CurrentRatesResponse(current.asOf(), current.rates().stream().map(RateResponse::from).toList());
    }

    record PutRatesRequest(@NotEmpty List<@NotNull @Valid RateRequest> rates) {
    }

    /** 1 {@code base} = {@code rate} {@code quote}. The rate is a JSON string (preferred) or number. */
    record RateRequest(
            @NotNull @Pattern(regexp = "SGD|USD|EUR|JPY", message = "must be one of SGD, USD, EUR, JPY") String base,
            @NotNull @Pattern(regexp = "SGD|USD|EUR|JPY", message = "must be one of SGD, USD, EUR, JPY") String quote,
            @NotNull @Positive @DecimalMax(value = "10000000000", inclusive = false) BigDecimal rate) {
    }

    record RatesResponse(List<RateResponse> rates) {
    }

    record CurrentRatesResponse(Instant asOf, List<RateResponse> rates) {
    }

    /** {@code rate} without trailing zeros, as {@code fxRate} is shown on transactions. */
    record RateResponse(String base, String quote, String rate, Instant effectiveFrom) {

        static RateResponse from(ExchangeRate r) {
            return new RateResponse(r.base().getCurrencyCode(), r.quote().getCurrencyCode(),
                    Money.normalize(r.rate()), r.effectiveFrom());
        }
    }
}
