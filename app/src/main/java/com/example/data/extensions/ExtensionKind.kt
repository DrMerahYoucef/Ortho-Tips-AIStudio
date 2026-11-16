package com.example.data.extensions

/**
 * What an `.OTips` package contains, as declared by its manifest's `kind`.
 *
 * Kinds are plain strings rather than an enum on purpose: the app decides what
 * it supports by which [ExtensionHandler]s are registered, so a package of a
 * kind this version does not know is recognised, reported as "needs a newer
 * version of Ortho Tips", and never half-installed. Adding a kind means writing
 * one handler and registering it; the container format, the import screen and
 * the manager list need no change.
 */
object ExtensionKinds {
    /** A self-contained web program run full screen (extension guide, sections 1–13). */
    const val HTML = "html"

    /** The operative guide: chapters, procedures, sections and figures shown natively. */
    const val OPERATIVE_GUIDE = "operative-guide"

    /** Classification reference: chapters of hierarchical classification documents. */
    const val CLASSIFICATIONS = "classifications"

    private val VALID = Regex("[a-z][a-z0-9-]{0,31}")

    fun isValid(kind: String): Boolean = VALID.matches(kind)
}
