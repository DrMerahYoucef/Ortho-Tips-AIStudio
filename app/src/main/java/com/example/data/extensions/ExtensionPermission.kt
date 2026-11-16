package com.example.data.extensions

/**
 * Capabilities an extension can ask for (extension guide, section 4.1).
 *
 * This enum is the single source of truth for that table: adding a capability
 * means adding one value here, one row in the guide, and the matching case in
 * `ExtensionBridge`, which denies anything it does not know. Nothing about the
 * `.OTips` manifest format has to change.
 *
 * Least privilege by default: a manifest without permissions gets none of these.
 *
 * @property key the name used in manifests and exposed to pages through
 *   `OrthoTips.permissions.granted`.
 * @property implemented false for reserved values: declaring them is harmless
 *   and future-proofs a manifest, but they unlock nothing yet.
 */
enum class ExtensionPermission(val key: String, val implemented: Boolean) {
    INTERNET("internet", implemented = true),
    DATABASE("database", implemented = true),

    /** Read, create and update patient records and add media (never delete). */
    PATIENTS("patients", implemented = true),

    /** Gemini through the app's own key (Réglages → Gemini); the key never reaches the extension. */
    GEMINI("gemini", implemented = true),
    STORAGE("storage", implemented = true),
    ASSETS("assets", implemented = true),
    IMPORT_EXPORT("import_export", implemented = true),

    /** Bank movements read from SMS (debits, credits, balances — never codes). */
    SMS("sms", implemented = true),
    /** Reminders the app shows as notifications, even when it is closed. */
    NOTIFICATIONS("notifications", implemented = true),
    /** Audio played by the app's media service: screen off, controls in the notification panel. */
    BACKGROUND("background", implemented = true);

    companion object {
        private val SEPARATORS = Regex("[\\s,;]+")

        /** Accepts `import_export`, `import-export` and any letter case. */
        fun fromKey(key: String): ExtensionPermission? {
            val normalized = key.trim().lowercase().replace('-', '_')
            return entries.firstOrNull { it.key == normalized }
        }

        /**
         * Parses a declaration such as `"internet, database"`. Unknown names are
         * ignored rather than rejected, so a manifest written for a newer app
         * still installs on an older one with the capabilities it knows.
         */
        fun parseList(text: String?): Set<ExtensionPermission> =
            text.orEmpty()
                .split(SEPARATORS)
                .mapNotNull { if (it.isBlank()) null else fromKey(it) }
                .toSortedSet()

        fun keys(permissions: Collection<ExtensionPermission>): List<String> =
            permissions.sortedBy { it.ordinal }.map { it.key }
    }
}
