package com.symphony.ledger.posting;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Request-hash normalization (DESIGN.md §7.3, §14.2). */
class RequestHasherTest {

    private static final UUID ALICE = UUID.fromString("a1111111-1111-4111-8111-111111111111");
    private static final UUID BOB = UUID.fromString("b2222222-2222-4222-8222-222222222222");

    /** The FIPS 180-2 test vector, as lowercase hex. */
    @Test
    void sha256Hex_isLowercaseHexSha256() {
        assertThat(RequestHasher.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void hashes_areOfTheCanonicalStrings() {
        assertThat(RequestHasher.deposit(ALICE, new BigDecimal("100.05"))).isEqualTo(RequestHasher.sha256Hex(
                "DEPOSIT|account=a1111111-1111-4111-8111-111111111111|amount=100.05"));
        assertThat(RequestHasher.transfer(ALICE, BOB, new BigDecimal("10.00"))).isEqualTo(RequestHasher.sha256Hex(
                "TRANSFER|source=a1111111-1111-4111-8111-111111111111"
                        + "|destination=b2222222-2222-4222-8222-222222222222|amount=10"));
        assertThat(RequestHasher.reversal(BOB)).isEqualTo(RequestHasher.sha256Hex(
                "REVERSAL|transaction=b2222222-2222-4222-8222-222222222222"));
    }

    @Test
    void uuids_areHashedLowercase_howeverTheyWereWritten() {
        UUID upper = UUID.fromString("A1111111-1111-4111-8111-111111111111");

        assertThat(RequestHasher.deposit(upper, BigDecimal.TEN)).isEqualTo(RequestHasher.deposit(ALICE, BigDecimal.TEN));
    }

    @ParameterizedTest
    @ValueSource(strings = {"100.0", "100.00", "100.0000", "1E+2", "1.00E+2"})
    void equalAmounts_hashTheSame_whateverTheirScale(String amount) {
        assertThat(RequestHasher.deposit(ALICE, new BigDecimal(amount)))
                .isEqualTo(RequestHasher.deposit(ALICE, new BigDecimal("100")));
        assertThat(RequestHasher.transfer(ALICE, BOB, new BigDecimal(amount)))
                .isEqualTo(RequestHasher.transfer(ALICE, BOB, new BigDecimal("100")));
    }

    /** Through {@code double} both of these would be 0.3, and a different request would replay the first one. */
    @Test
    void amountsThatOnlyDoubleWouldConfuse_hashDifferently() {
        assertThat(BigDecimal.valueOf(Double.parseDouble("0.30000000000000001"))).isEqualByComparingTo("0.3");

        assertThat(RequestHasher.deposit(ALICE, new BigDecimal("0.30000000000000001")))
                .isNotEqualTo(RequestHasher.deposit(ALICE, new BigDecimal("0.3")));
        assertThat(RequestHasher.transfer(ALICE, BOB, new BigDecimal("0.30000000000000001")))
                .isNotEqualTo(RequestHasher.transfer(ALICE, BOB, new BigDecimal("0.3")));
    }

    @Test
    void anythingElseThatDiffers_changesTheHash() {
        BigDecimal ten = BigDecimal.TEN;

        assertThat(RequestHasher.deposit(ALICE, ten)).isNotEqualTo(RequestHasher.deposit(BOB, ten));
        assertThat(RequestHasher.deposit(ALICE, ten)).isNotEqualTo(RequestHasher.deposit(ALICE, new BigDecimal("10.01")));
        assertThat(RequestHasher.transfer(ALICE, BOB, ten)).isNotEqualTo(RequestHasher.transfer(BOB, ALICE, ten));
        assertThat(RequestHasher.reversal(ALICE)).isNotEqualTo(RequestHasher.reversal(BOB));
        // The operation is part of the hash, so a key reused across operations is a different body (§7.2).
        assertThat(RequestHasher.deposit(ALICE, ten)).isNotEqualTo(RequestHasher.transfer(ALICE, BOB, ten));
    }
}
