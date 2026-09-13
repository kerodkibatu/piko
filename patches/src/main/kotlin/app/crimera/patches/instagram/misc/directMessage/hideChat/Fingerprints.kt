/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.directMessage.hideChat

import app.crimera.patches.instagram.misc.directMessage.markChatAsReadPatch.ThreadLongPressButtonStringListFingerprint
import app.crimera.patches.instagram.utils.Constants.PATCHES_DESCRIPTOR
import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

// The DM thread deserializer hook is declared locally to keep this patch
// self-contained. The long-press dialog injection point is identified from
// the target APK (see HideChatPatch for details).
internal const val EXTENSION_CLASS_NAME = "${PATCHES_DESCRIPTOR}/dm/HideChat;"
internal const val INJECTOR_CLASS_NAME = "${PATCHES_DESCRIPTOR}/dm/HideChatInjector;"

/**
 * The static long-press dialog builder (LX/08r4.A00 in v439): builds the
 * thread long-press dialog, copies the stock row models into a list, and
 * hands it to the dialog controller. Identified structurally (static, 17
 * params including DirectThreadKey and the row List).
 */
internal object ThreadLongPressDialogBuilderFingerprint : Fingerprint(
    classFingerprint = ThreadLongPressButtonStringListFingerprint,
    custom = { methodDef, _ ->
        AccessFlags.STATIC.isSet(methodDef.accessFlags) &&
            methodDef.returnType == "V" &&
            methodDef.parameters.size == 17 &&
            methodDef.parameters.any { it.type == "Lcom/instagram/model/direct/DirectThreadKey;" } &&
            methodDef.parameters.any { it.type == "Ljava/util/List;" }
    },
)

/**
 * The class containing the thread JSON parser (LX/0AMg in v439),
 * identified by the thread-field keys its parse method reads.
 * The returnType narrows it to the void parse method (A00) that
 * contains these strings.
 */
internal object HideChatThreadDeserializerClassFingerprint : Fingerprint(
    strings = listOf("users", "admin_user_ids", "left_users", "thread_v2_id", "input_mode"),
    returnType = "V",
)

/**
 * The thread deserializer bridge (LX/0AMg.unsafeParseFromJson in v439).
 * NOTE: the string-heavy parse method (A00) returns void, so it cannot be
 * filtered; the bridge is what returns the deserialized thread object.
 */
internal object HideChatThreadDeserializerFingerprint : Fingerprint(
    classFingerprint = HideChatThreadDeserializerClassFingerprint,
    custom = { methodDef, _ ->
        methodDef.returnType == "Ljava/lang/Object;" &&
            methodDef.name == "unsafeParseFromJson"
    },
)
