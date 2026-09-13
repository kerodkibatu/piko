/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.directMessage.hideChat

import app.crimera.patches.instagram.entity.userdata.userDataEntity
import app.crimera.patches.instagram.misc.directMessage.markChatAsReadPatch.ThreadLongPressButtonActionFingerprint
import app.crimera.patches.instagram.misc.directMessage.markChatAsReadPatch.ThreadLongPressButtonsEnumInitFingerprint
import app.crimera.patches.instagram.misc.directMessage.markChatAsReadPatch.ThreadLongPressMuteButtonBuilderFingerprint
import app.crimera.patches.instagram.misc.settings.settingsPatch
import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.crimera.patches.instagram.utils.Constants.USER_SESSION_CLASS
import app.crimera.patches.instagram.utils.enableSettings
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.util.registersUsed
import com.android.tools.smali.dexlib2.Opcode

@Suppress("unused")
val hideChatPatch =
    bytecodePatch(
        name = "Hide chat",
        description = "Adds option to hide a chat on long press. Hidden chats are listed under Piko settings where they can be unhidden",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch, userDataEntity, hideChatResourcePatch)
        execute {
            val buttonEnumClassName: String
            ThreadLongPressButtonsEnumInitFingerprint.apply {
                buttonEnumClassName = classDef.type
            }

            // TODO(hide-chat): sections 1-2 are a placeholder. The enum list is
            // NOT a safe injection point (no HIDE enum constant exists). Replace
            // with the target-APK-evidenced long-press dialog row injection
            // before shipping: locate the class containing "[DEBUG] Thread info"
            // and the dialog row builder (see HideChat.java addButton/buttonAction
            // for the intended extension API).
            // 1. Long-press menu: append the "Hide chat" entry to the button list.
            ThreadLongPressMuteButtonBuilderFingerprint.method.apply {
                val listParameterIndex = parameterTypes.indexOf("Ljava/util/List;")
                addInstructions(
                    0,
                    """
                    invoke-static {p$listParameterIndex}, $EXTENSION_CLASS_NAME->addButton(Ljava/util/List;)Ljava/util/List;
                    move-result-object p$listParameterIndex

                    """.trimIndent(),
                )
            }

            // 2. Long-press menu: consume the press when our entry is tapped.
            ThreadLongPressButtonActionFingerprint.apply {
                val directThreadKeyClassName = "Lcom/instagram/model/direct/DirectThreadKey;"
                val context = "Landroid/content/Context;"

                val userSessionFieldRef = classDef.fields.first { it.type == USER_SESSION_CLASS }
                val contextFieldRef = classDef.fields.first { it.type == context }

                method.apply {
                    val buttonParameterIndex = parameters.indexOfFirst { it.type == buttonEnumClassName } + 1
                    val directThreadKeyParameterIndex = parameters.indexOfFirst { it.type == directThreadKeyClassName } + 1
                    val threadInfoParameterIndex = directThreadKeyParameterIndex - 2

                    // Hard coding register names as these instructions
                    // will be added on the first line.
                    addInstructionsWithLabels(
                        0,
                        """
                        move-object/from16 v0, p0
                        iget-object v1, v0, $contextFieldRef
                        iget-object v2, v0, $userSessionFieldRef

                        move-object/from16 v3, p$buttonParameterIndex

                        move-object/from16 v4, p$threadInfoParameterIndex
                        move-object/from16 v5, p$directThreadKeyParameterIndex

                        invoke-static {v1,v2,v3,v4,v5}, $EXTENSION_CLASS_NAME->buttonAction(Landroid/content/Context;$USER_SESSION_CLASS;Ljava/lang/Object;Ljava/lang/Object;$directThreadKeyClassName)Z
                        move-result v0
                        if-eqz v0, :piko
                        return-void
                        """.trimIndent(),
                        ExternalLabel("piko", getInstruction(0)),
                    )
                }
            }

            // 3. Inbox list: drop hidden threads as they are deserialized,
            // so they never reach the inbox adapter. Returning null removes
            // the thread; anything else passes through untouched.
            HideChatThreadDeserializerFingerprint.apply {
                method.apply {
                    if (returnType != "V") {
                        instructions
                            .filter { it.opcode == Opcode.RETURN_OBJECT }
                            .map { it.location.index to it.registersUsed[0] }
                            .sortedByDescending { it.first }
                            .forEach { (index, register) ->
                                addInstructions(
                                    index,
                                    """
                                    invoke-static {v$register}, $EXTENSION_CLASS_NAME->filter(Ljava/lang/Object;)Ljava/lang/Object;
                                    move-result-object v$register
                                    """.trimIndent(),
                                )
                            }
                    }
                }
            }

            enableSettings("hideChat")
        }
    }
