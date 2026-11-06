package eu.hansolo.mcp.server;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import eu.hansolo.mcp.protocol.McpProtocolException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Signs the {@code requestState} of a Multi Round-Trip Request. The state travels through the client, which can read, alter and
 * replay it, so a tool must never trust it as it arrives. This class makes it tamper-evident and short-lived:
 *
 * <pre>{@code
 * return ToolResult.inputRequired(request, states.sign("step=2;name=" + name));
 * ...
 * String payload = states.require(call.requestState());   // throws -32602 if altered, expired or foreign
 * }</pre>
 *
 * <p>The state is signed, not encrypted: do not put secrets into it. The optional {@code context} (for example the tool name and
 * the caller's subject) is part of the signature but not of the state, so a state issued for one tool or user is refused for another.
 *
 * <p>The key is {@code mcp.request-state.secret}. Without it a random key is created per instance: states then do not survive a
 * restart and are refused by other nodes, so set the same secret on every node of a cluster.
 */
@Singleton
public class McpRequestStates {

    private static final Logger         LOG     = Logger.getLogger(McpRequestStates.class.getName());
    private static final String         HMAC    = "HmacSHA256";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final SecretKeySpec key;
    private final long          ttlMs;


    @Inject
    public McpRequestStates(McpConfig config) {
        this(config.requestStateSecret(), config.requestStateTtlMs());
    }

    McpRequestStates(String secret, long ttlMs) {
        byte[] bytes;
        if (secret == null) {
            bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            LOG.info("mcp.request-state.secret is not set: request states are signed with a random key that is valid on this instance only");
        } else {
            bytes = secret.getBytes(StandardCharsets.UTF_8);
        }
        this.key   = new SecretKeySpec(bytes, HMAC);
        this.ttlMs = ttlMs;
    }


    public String sign(String payload) {
        return sign(payload, "");
    }

    /** Signs the payload for this context; the same context must be given to {@link #verify}. */
    public String sign(String payload, String context) {
        return sign(payload, context, System.currentTimeMillis() + ttlMs);
    }

    String sign(String payload, String context, long expiresAtMillis) {
        String body = expiresAtMillis + "." + ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return body + "." + ENCODER.encodeToString(mac(body, context));
    }

    public Optional<String> verify(String state) {
        return verify(state, "");
    }

    /** The payload if the state is authentic, unexpired and was issued for this context; empty otherwise. */
    public Optional<String> verify(String state, String context) {
        if (state == null) {
            return Optional.empty();
        }
        String[] parts = state.split("\\.", -1);
        if (parts.length != 3) {
            return Optional.empty();
        }
        try {
            byte[] given = DECODER.decode(parts[2]);
            if (!MessageDigest.isEqual(given, mac(parts[0] + "." + parts[1], context))) {
                return Optional.empty();
            }
            if (Long.parseLong(parts[0]) < System.currentTimeMillis()) {
                return Optional.empty();
            }
            return Optional.of(new String(DECODER.decode(parts[1]), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public String require(String state) {
        return require(state, "");
    }

    /**
     * Like {@link #verify} but reports failure to the client as an invalid-params error (HTTP 200, code -32602). Use this for a retry
     * that must carry a state.
     */
    public String require(String state, String context) {
        return verify(state, context).orElseThrow(() -> McpProtocolException.invalidArguments("requestState integrity check failed"));
    }

    private byte[] mac(String body, String context) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(key);
            mac.update(context.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            return mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }
}
