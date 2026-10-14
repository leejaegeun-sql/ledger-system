package com.symphony.ledger.fx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.symphony.ledger.support.IntegrationTestBase;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.web.servlet.ResultActions;

/**
 * {@code PUT /exchange-rates} and {@code GET /exchange-rates} (DESIGN.md §8.9, §8.10, §10).
 *
 * <p>Rates are global and the database is shared, so every test that sets a rate runs at its own
 * far-future clock and sets the rates it relies on. Tests at the default clock never see these rates.
 */
class ExchangeRateIT extends IntegrationTestBase {

    @Test
    void put_thenGetShowsTheNewRate_andNewTransfersUseIt() throws Exception {
        clock.set(Instant.parse("2200-01-01T00:00:00Z"));
        UUID alice = createAccount("Alice", "SGD");
        UUID bob = createAccount("Bob", "USD");
        fund(alice, "500.00");
        String before = JsonPath.read(created(transfer(alice, bob, "\"10.00\"", newKey())), "$.id");
        String rateBefore = fxRateOf(before);

        putRates("{\"rates\":[{\"base\":\"SGD\",\"quote\":\"USD\",\"rate\":\"0.75\"}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rates.length()").value(1))
                .andExpect(jsonPath("$.rates[0].base").value("SGD"))
                .andExpect(jsonPath("$.rates[0].quote").value("USD"))
                .andExpect(jsonPath("$.rates[0].rate").value("0.75"))
                .andExpect(jsonPath("$.rates[0].effectiveFrom").value("2200-01-01T00:00:00Z"));
        clock.advance(Duration.ofMinutes(5));

        getRates()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asOf").value("2200-01-01T00:05:00Z"))
                .andExpect(jsonPath("$.rates[?(@.base == 'SGD' && @.quote == 'USD')].rate").value(contains("0.75")))
                .andExpect(jsonPath("$.rates[?(@.base == 'SGD' && @.quote == 'USD')].effectiveFrom")
                        .value(contains("2200-01-01T00:00:00Z")));
        transfer(alice, bob, "\"100.00\"", newKey())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fxRate").value("0.75"))
                .andExpect(jsonPath("$.destinationAmount").value("75.00"));

        // A transaction posted earlier keeps the rate it was posted at.
        assertThat(rateBefore).isNotEqualTo("0.75");
        assertThat(fxRateOf(before)).isEqualTo(rateBefore);
    }

    /** All pairs that have a rate, sorted by base then quote; numbers are accepted as well as strings. */
    @Test
    void put_severalPairsAtOnce_appliesAll_andGetListsEveryPairInOrder() throws Exception {
        clock.set(Instant.parse("2210-01-01T00:00:00Z"));

        putRates("""
                {"rates":[{"base":"EUR","quote":"JPY","rate":160.5},{"base":"JPY","quote":"EUR","rate":"0.0062000"}]}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rates[*].rate").value(contains("160.5", "0.0062")));

        String body = getRates().andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> pairs = JsonPath.read(body, "$.rates[*].base");
        List<String> quotes = JsonPath.read(body, "$.rates[*].quote");
        for (int i = 0; i < pairs.size(); i++) {
            pairs.set(i, pairs.get(i) + "/" + quotes.get(i));
        }
        assertThat(pairs).hasSize(12).isSorted().doesNotHaveDuplicates();
        assertThat(JsonPath.<List<String>>read(body, "$.rates[?(@.base == 'EUR' && @.quote == 'JPY')].rate"))
                .containsExactly("160.5");
        assertThat(JsonPath.<List<String>>read(body, "$.rates[?(@.base == 'JPY' && @.quote == 'EUR')].rate"))
                .containsExactly("0.0062");
    }

    /** Each PUT adds a row; earlier rates stay and still apply to the times they covered (DESIGN.md §8.9). */
    @Test
    void put_keepsEarlierRatesAsHistory() throws Exception {
        Instant first = Instant.parse("2220-01-01T00:00:00Z");
        clock.set(first);
        long rows = countRows("exchange_rates");
        putRates("{\"rates\":[{\"base\":\"USD\",\"quote\":\"EUR\",\"rate\":\"0.950000000000\"}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rates[0].rate").value("0.95"));
        clock.set(first.plus(Duration.ofHours(1)));
        putRates("{\"rates\":[{\"base\":\"USD\",\"quote\":\"EUR\",\"rate\":\"0.96\"}]}").andExpect(status().isOk());

        assertThat(countRows("exchange_rates")).isEqualTo(rows + 2);
        getRates().andExpect(jsonPath("$.rates[?(@.base == 'USD' && @.quote == 'EUR')].rate").value(contains("0.96")));

        clock.set(first.plus(Duration.ofMinutes(30)));
        getRates().andExpect(jsonPath("$.rates[?(@.base == 'USD' && @.quote == 'EUR')].rate").value(contains("0.95")));
        UUID alice = createAccount("Alice", "USD");
        UUID eve = createAccount("Eve", "EUR");
        fund(alice, "100.00");
        transfer(alice, eve, "\"100.00\"", newKey())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fxRate").value("0.95"))
                .andExpect(jsonPath("$.destinationAmount").value("95.00"));
    }

    static Stream<String> invalidRequests() {
        return Stream.of(
                "{\"rates\":[]}",
                "{}",
                "{\"rates\":[{\"base\":\"GBP\",\"quote\":\"USD\",\"rate\":\"1.2\"}]}",
                "{\"rates\":[{\"base\":\"SGD\",\"quote\":\"usd\",\"rate\":\"1.2\"}]}",
                "{\"rates\":[{\"base\":\"SGD\",\"quote\":\"SGD\",\"rate\":\"1\"}]}",
                "{\"rates\":[{\"base\":\"SGD\",\"quote\":\"USD\",\"rate\":\"0\"}]}",
                "{\"rates\":[{\"base\":\"SGD\",\"quote\":\"USD\",\"rate\":\"-0.75\"}]}",
                "{\"rates\":[{\"base\":\"SGD\",\"quote\":\"USD\",\"rate\":\"0.12345678901\"}]}",
                "{\"rates\":[{\"base\":\"SGD\",\"quote\":\"USD\",\"rate\":\"10000000000\"}]}",
                "{\"rates\":[{\"base\":\"SGD\",\"quote\":\"USD\",\"rate\":\"ten\"}]}",
                "{\"rates\":[{\"base\":\"SGD\",\"quote\":\"USD\"}]}",
                "{\"rates\":[{\"quote\":\"USD\",\"rate\":\"0.75\"}]}",
                "{\"rates\":[null]}",
                // A duplicate pair, and a valid item next to an invalid one: nothing is applied.
                "{\"rates\":[{\"base\":\"SGD\",\"quote\":\"USD\",\"rate\":\"0.8\"},{\"base\":\"SGD\",\"quote\":\"USD\",\"rate\":\"0.9\"}]}",
                "{\"rates\":[{\"base\":\"SGD\",\"quote\":\"USD\",\"rate\":\"0.8\"},{\"base\":\"EUR\",\"quote\":\"EUR\",\"rate\":\"1\"}]}",
                "{\"rates\":");
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void put_invalidRequest_returns400AndWritesNothing(String body) throws Exception {
        long rows = countRows("exchange_rates");

        putRates(body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/validation-error"));

        assertThat(countRows("exchange_rates")).isEqualTo(rows);
    }

    private ResultActions getRates() throws Exception {
        return mvc.perform(get("/exchange-rates"));
    }

    private String fxRateOf(String transactionId) throws Exception {
        String body = mvc.perform(get("/transactions/{id}", transactionId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.fxRate");
    }

    private static String created(ResultActions posted) throws Exception {
        return posted.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    }
}
