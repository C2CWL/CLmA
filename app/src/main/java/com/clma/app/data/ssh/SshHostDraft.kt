package me.rerere.rikkahub.data.ssh

/**
 * P2-32: pure model + validation behind the SSH host settings page.
 *
 * Deliberately free of Room / Android / Compose imports: the editor rules are identical no
 * matter who opened the page, and they can be unit-tested by the kotlinc harness on the VPS
 * (`sh /workspace/ktest.sh ...`) without an Android SDK.
 */

/** Which secret the editor collects. Mirrors the two nullable secret columns of `SshHostEntity`. */
enum class SshAuthMethod { PASSWORD, PRIVATE_KEY }

/** Which editor row an error belongs to, so the sheet can highlight the offending field. */
enum class SshHostField { NAME, HOST, PORT, USER, CREDENTIAL }

/** What is wrong with a [SshHostField]. */
enum class SshHostError {
    NAME_BLANK,
    NAME_TAKEN,
    HOST_BLANK,
    PORT_INVALID,
    USER_BLANK,
    CREDENTIAL_MISSING,
}

/**
 * Editable snapshot of one saved SSH host.
 *
 * [originalName] is the name the row had when the editor opened (null for a new host); it is
 * what lets a rename be told apart from a collision with an existing name.
 */
data class SshHostDraft(
    val originalName: String? = null,
    val name: String = "",
    val host: String = "",
    val port: String = "22",
    val user: String = "",
    val authMethod: SshAuthMethod = SshAuthMethod.PASSWORD,
    val password: String = "",
    val privateKey: String = "",
    val passphrase: String = "",
) {
    val isNew: Boolean get() = originalName == null

    /** Parsed port, or null when the text is not an integer in 1..65535. */
    fun portOrNull(): Int? = port.trim().toIntOrNull()?.takeIf { it in 1..65535 }

    /** Whether the secret field for the currently selected method has content. */
    fun hasSecret(): Boolean = when (authMethod) {
        SshAuthMethod.PASSWORD -> password.isNotBlank()
        SshAuthMethod.PRIVATE_KEY -> privateKey.isNotBlank()
    }
}

object SshHostDraftValidator {
    /**
     * @param existingNames names currently in the database. A rename onto one of them is
     *   rejected; editing a host without touching its name is not a collision.
     */
    fun validate(draft: SshHostDraft, existingNames: Set<String>): Map<SshHostField, SshHostError> {
        val errors = LinkedHashMap<SshHostField, SshHostError>()
        val name = draft.name.trim()
        if (name.isEmpty()) {
            errors[SshHostField.NAME] = SshHostError.NAME_BLANK
        } else if (name != draft.originalName?.trim() && name in existingNames) {
            errors[SshHostField.NAME] = SshHostError.NAME_TAKEN
        }
        if (draft.host.isBlank()) errors[SshHostField.HOST] = SshHostError.HOST_BLANK
        if (draft.portOrNull() == null) errors[SshHostField.PORT] = SshHostError.PORT_INVALID
        if (draft.user.isBlank()) errors[SshHostField.USER] = SshHostError.USER_BLANK
        if (!draft.hasSecret()) errors[SshHostField.CREDENTIAL] = SshHostError.CREDENTIAL_MISSING
        return errors
    }

    fun isValid(draft: SshHostDraft, existingNames: Set<String>): Boolean =
        validate(draft, existingNames).isEmpty()
}

/** One entry of the app's `known_hosts` file, as shown on the settings page. */
data class KnownHostEntry(val pattern: String, val keyType: String) {
    /** OpenSSH hashed host entries start with `|1|`; those cannot be mapped back to a name. */
    val isHashed: Boolean get() = pattern.startsWith("|")
}

/**
 * Parse an OpenSSH known_hosts file.
 *
 * Each line is `[marker] pattern keytype base64key`. Only the pattern (host) and key type are
 * kept, since that is all the page shows. Blank / comment / malformed lines are skipped rather
 * than throwing — a user-edited known_hosts must never crash the settings page.
 */
fun parseKnownHosts(text: String): List<KnownHostEntry> =
    text.lineSequence().mapNotNull { rawLine ->
        val line = rawLine.trim()
        if (line.isEmpty() || line.startsWith("#")) return@mapNotNull null
        val parts = line.split(' ', '\t').filter { it.isNotEmpty() }
        val start = if (parts.firstOrNull()?.startsWith("@") == true) 1 else 0
        val pattern = parts.getOrNull(start) ?: return@mapNotNull null
        val keyType = parts.getOrNull(start + 1) ?: return@mapNotNull null
        KnownHostEntry(pattern = pattern, keyType = keyType)
    }.toList()
