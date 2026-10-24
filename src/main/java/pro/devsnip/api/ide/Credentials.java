package pro.devsnip.api.ide;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.CredentialAttributesKt;

/**
 * Builds {@link CredentialAttributes} through the {@code (String, String)} constructor.
 * It is the one constructor that exists and is not deprecated on every supported
 * platform (2024.1 to current), and only Java can call it directly: Kotlin binds to
 * the default-arguments overload, which newer platforms deprecate.
 */
final class Credentials {
    private Credentials() {
    }

    static CredentialAttributes attributes(String key) {
        return new CredentialAttributes(CredentialAttributesKt.generateServiceName("DevSnip Pro API Client", key), null);
    }
}
