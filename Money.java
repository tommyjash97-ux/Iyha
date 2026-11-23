package net.iyha.core;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.OptionalLong;

/** Money is stored as whole hundredths in a long. No floating point is used for balances. */
final class Money {
    static final long SCALE = 100L;

    private Money() {}

    /** Parses 100, 2.5, 1,000, 2.5k, 3m, 1b, 2t. Returns empty if invalid or not positive. */
    static OptionalLong parse(String input) {
        if (input == null) {
            return OptionalLong.empty();
        }
        String s = input.trim().replace(",", "").replace("_", "").toLowerCase(Locale.ROOT);
        if (s.isEmpty()) {
            return OptionalLong.empty();
        }
        BigDecimal multiplier = BigDecimal.ONE;
        char last = s.charAt(s.length() - 1);
        switch (last) {
            case 'k' -> multiplier = BigDecimal.valueOf(1_000L);
            case 'm' -> multiplier = BigDecimal.valueOf(1_000_000L);
            case 'b' -> multiplier = BigDecimal.valueOf(1_000_000_000L);
            case 't' -> multiplier = BigDecimal.valueOf(1_000_000_000_000L);
            default -> { }
        }
        if (multiplier.compareTo(BigDecimal.ONE) != 0) {
            s = s.substring(0, s.length() - 1);
        }
        if (!s.matches("\\d+(\\.\\d+)?|\\.\\d+")) {
            return OptionalLong.empty();
        }
        try {
            BigDecimal value = new BigDecimal(s).multiply(multiplier);
            if (value.stripTrailingZeros().scale() > 2) {
                return OptionalLong.empty();
            }
            long cents = value.movePointRight(2).longValueExact();
            return cents > 0 ? OptionalLong.of(cents) : OptionalLong.empty();
        } catch (ArithmeticException | NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    /** 123456 -> "1,234.56", 100000 -> "1,000". */
    static String plain(long cents) {
        DecimalFormat df = new DecimalFormat(cents % SCALE == 0 ? "#,##0" : "#,##0.00",
                DecimalFormatSymbols.getInstance(Locale.US));
        return df.format(BigDecimal.valueOf(cents, 2));
    }

    static long fromDouble(double amount) {
        return Math.round(amount * SCALE);
    }
}
