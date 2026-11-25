package com.robomori

import java.io.File
import java.security.MessageDigest

/**
 * A shell check that prints ROBOMORI_PRESENT when [remote] is on the device at [size] bytes, so a helper is pushed only
 * when it's missing or a push was cut short.
 */
internal fun deviceFileMatches(remote: String, size: Long) =
    "[ \"$(stat -c %s ${quoteShell(remote)} 2>/dev/null)\" = \"$size\" ] && echo ROBOMORI_PRESENT || echo ROBOMORI_MISSING"

/** A short hash of [file]'s contents, naming each build of a helper on the device. */
internal fun contentVersion(file: File): String =
    MessageDigest.getInstance("SHA-256").digest(file.readBytes()).take(8).joinToString("") { "%02x".format(it) }
