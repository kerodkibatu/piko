/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.directMessage.hideChat

import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element

/**
 * Registers the hidden-chats viewer activity in the Android manifest. Kept
 * separate from the shared settings resource patch so the activity is only
 * added when the "Hide chat" patch is actually applied.
 */
val hideChatResourcePatch =
    resourcePatch(
        description = "Adds the hidden-chats viewer activity to the Android manifest.",
    ) {
        finalize {
            document("AndroidManifest.xml").use { document ->
                val application = document.getElementsByTagName("application").item(0) as Element

                val activity = document.createElement("activity")
                activity.setAttribute("android:name", "app.morphe.extension.instagram.patches.dm.HiddenChatsActivity")
                activity.setAttribute("android:theme", "@android:style/Theme.DeviceDefault.NoActionBar")
                activity.setAttribute("android:exported", "false")
                application.appendChild(activity)
            }
        }
    }
