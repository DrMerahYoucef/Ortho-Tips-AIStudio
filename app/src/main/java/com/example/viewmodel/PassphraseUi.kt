package com.example.viewmodel

/** Result of the last passphrase action, turned into text by the screen. */
enum class PassphraseOutcome { SAVED, UNLOCKED, WRONG, NO_BACKUP, LOCKED_DRIVE, TOO_SHORT, FAILED }

data class PassphraseUi(
    /** False until the drive has been scanned once. */
    val checked: Boolean = false,
    /** A key backup exists in the drive. */
    val hasBackup: Boolean = false,
    /** The drive holds data this phone cannot open yet. */
    val needsUnlock: Boolean = false,
    val lockedObjects: Int = 0,
    val working: Boolean = false,
    val outcome: PassphraseOutcome? = null
)
