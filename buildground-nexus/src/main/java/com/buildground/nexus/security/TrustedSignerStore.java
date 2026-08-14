package com.buildground.nexus.security;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Local trust anchor for BuildGround plugin signing certificates.
 *
 * There is intentionally no remote revocation or approval service in Phase 1.
 * Unknown signers fail closed until explicitly trusted by a future BuildGround-owned admin flow.
 */
public final class TrustedSignerStore {
    private static final String PREFS = "buildground_nexus_trusted_signers";
    private static final String KEY_TRUSTED = "sha256";
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private final SharedPreferences preferences;

    public TrustedSignerStore(Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean isTrusted(String signerSha256) {
        String normalized = normalize(signerSha256);
        return normalized != null && read().contains(normalized);
    }

    public Set<String> allTrusted() {
        return Collections.unmodifiableSet(read());
    }

    /** Package-private on purpose: only BuildGround-owned security/admin code should mutate trust. */
    void trust(String signerSha256) {
        String normalized = requireDigest(signerSha256);
        Set<String> updated = read();
        updated.add(normalized);
        preferences.edit().putStringSet(KEY_TRUSTED, updated).commit();
    }

    void revoke(String signerSha256) {
        String normalized = normalize(signerSha256);
        if (normalized == null) return;
        Set<String> updated = read();
        updated.remove(normalized);
        preferences.edit().putStringSet(KEY_TRUSTED, updated).commit();
    }

    private Set<String> read() {
        return new HashSet<>(preferences.getStringSet(KEY_TRUSTED, Collections.emptySet()));
    }

    private static String requireDigest(String value) {
        String normalized = normalize(value);
        if (normalized == null) throw new IllegalArgumentException("Expected lowercase/uppercase SHA-256 digest");
        return normalized;
    }

    private static String normalize(String value) {
        if (value == null) return null;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return SHA256.matcher(normalized).matches() ? normalized : null;
    }
}
