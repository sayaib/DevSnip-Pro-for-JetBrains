package pro.devsnip.api.ide

import com.intellij.ide.passwordSafe.PasswordSafe

/** Where secrets live. The IDE implementation is the system keychain via PasswordSafe. */
interface SecretStore {
    fun get(key: String): String?

    /** null or empty removes the secret. */
    fun set(key: String, value: String?)
}

/**
 * Stores secrets in the IDE password safe (macOS Keychain, Windows Credential
 * Manager, KeePass or libsecret, per the IDE's settings). Calls can block, so
 * they are made off the EDT.
 */
class PasswordSafeSecretStore : SecretStore {
    private fun attributes(key: String) = Credentials.attributes(key)

    override fun get(key: String): String? = PasswordSafe.instance.getPassword(attributes(key))

    override fun set(key: String, value: String?) {
        PasswordSafe.instance.setPassword(attributes(key), value?.ifEmpty { null })
    }
}
