/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.directMessage.hideChat

import app.crimera.patches.instagram.utils.Constants.PATCHES_DESCRIPTOR
import app.morphe.patcher.Fingerprint

// The DM thread deserializer hook is declared locally to keep this patch
// self-contained. The long-press dialog injection point is identified from
// the target APK (see HideChatPatch for details).
internal const val EXTENSION_CLASS_NAME = "${PATCHES_DESCRIPTOR}/dm/HideChat;"

internal object HideChatThreadDeserializerFingerprint : Fingerprint(
    strings = listOf("users", "admin_user_ids", "left_users", "thread_v2_id", "input_mode"),
)
