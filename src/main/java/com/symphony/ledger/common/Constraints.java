package com.symphony.ledger.common;

import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

/** Which database constraint an exception violated (DESIGN.md §7.4). */
public final class Constraints {

    private Constraints() {
    }

    /** Read from the driver's error fields, never from message text. Null if it isn't a PostgreSQL error. */
    public static String violated(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof PSQLException psql) {
                ServerErrorMessage message = psql.getServerErrorMessage();
                return message == null ? null : message.getConstraint();
            }
        }
        return null;
    }
}
