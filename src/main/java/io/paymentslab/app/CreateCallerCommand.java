package io.paymentslab.app;

import io.paymentslab.api.Caller;
import io.paymentslab.api.CallerRole;
import io.paymentslab.api.Callers;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code create-caller --role CUSTOMER|OPERATOR --name NAME}: creates a caller and prints its API
 * key once, to standard output. The key never goes to the log, which may be kept or shipped elsewhere.
 */
public final class CreateCallerCommand {

    public static final String USAGE = "usage: payments-lab create-caller --role CUSTOMER|OPERATOR --name NAME";

    private static final Logger log = LoggerFactory.getLogger(CreateCallerCommand.class);

    public record Options(CallerRole role, String name) {}

    private CreateCallerCommand() {}

    /** Parses the arguments after the command name. @throws IllegalArgumentException saying what is wrong */
    public static Options parse(List<String> args) {
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i < args.size(); i += 2) {
            String flag = args.get(i);
            if (!flag.equals("--role") && !flag.equals("--name")) {
                throw new IllegalArgumentException("unknown option " + flag);
            }
            if (i + 1 >= args.size()) {
                throw new IllegalArgumentException(flag + " needs a value");
            }
            if (values.putIfAbsent(flag, args.get(i + 1)) != null) {
                throw new IllegalArgumentException(flag + " given twice");
            }
        }
        String role = values.get("--role");
        if (role == null) {
            throw new IllegalArgumentException("--role is required");
        }
        String name = values.get("--name");
        if (name == null) {
            throw new IllegalArgumentException("--name is required");
        }
        return new Options(parseRole(role), name);
    }

    /** Creates the caller. Returns the process exit code: 0 created, 1 refused. */
    public static int run(DSLContext db, Options options, PrintStream out, PrintStream err) {
        Callers.Created created;
        try {
            created = new Callers().create(db, options.name(), options.role());
        } catch (IllegalArgumentException refused) {
            err.println("error: " + refused.getMessage());
            return 1;
        }
        Caller caller = created.caller();
        log.info("created {} caller {} ({})", caller.role(), caller.name(), caller.id());
        out.println("Created " + caller.role() + " caller " + caller.name() + " (" + caller.id() + ").");
        out.println("API key (shown only now; only its hash is stored):");
        out.println(created.apiKey());
        return 0;
    }

    /** Exact match, like the database CHECK: "customer" is rejected, not normalized. */
    private static CallerRole parseRole(String role) {
        for (CallerRole candidate : CallerRole.values()) {
            if (candidate.name().equals(role)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("--role must be CUSTOMER or OPERATOR");
    }
}
