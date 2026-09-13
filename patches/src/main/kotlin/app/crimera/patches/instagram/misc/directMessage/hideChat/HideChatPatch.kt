/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.directMessage.hideChat

import app.crimera.patches.instagram.entity.userdata.userDataEntity
import app.crimera.patches.instagram.misc.settings.settingsPatch
import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.crimera.patches.instagram.utils.enableSettings
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val DIRECT_THREAD_KEY_CLASS = "Lcom/instagram/model/direct/DirectThreadKey;"

/** Extracts the MethodReference from an instruction, if it has one. */
private fun methodRefOrNull(insn: Any): MethodReference? {
    val refIns = insn as? ReferenceInstruction ?: return null
    return refIns.reference as? MethodReference
}

/** Gets the register numbers used by a five-register instruction (e.g. invoke-static {v6, v5}). */
private fun fiveRegisters(insn: Any): List<Int> {
    val five = insn as? FiveRegisterInstruction ?: return emptyList()
    return listOf(five.registerC, five.registerD, five.registerE, five.registerF, five.registerG)
        .take(five.registerCount)
}

/** Gets the single register used by an 11x instruction (e.g. return-object v0). */
private fun singleRegister(insn: Any): Int {
    val one = insn as? OneRegisterInstruction
        ?: throw IllegalStateException("HideChat: expected single-register instruction")
    return one.registerA
}

@Suppress("unused")
val hideChatPatch =
    bytecodePatch(
        name = "Hide chat",
        description = "Adds option to hide a chat on long press. Hidden chats are listed under Piko settings where they can be unhidden",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch, userDataEntity, hideChatResourcePatch)
        execute {
            // 1. Stash the thread id when the long-press dialog builder starts.
            // The builder is static, so pN maps directly to parameters[N].
            ThreadLongPressDialogBuilderFingerprint.method.apply {
                val threadKeyParamIndex =
                    parameters.indexOfFirst { it.type == DIRECT_THREAD_KEY_CLASS }
                if (threadKeyParamIndex == -1) {
                    throw IllegalStateException("HideChat: DirectThreadKey param not found")
                }
                // Pass the thread key object; the injector extracts the ID via reflection.
                // This avoids inline field access and register allocation issues.
                addInstructions(
                    0,
                    """
                    invoke-static {p$threadKeyParamIndex}, $INJECTOR_CLASS_NAME->stashThreadKey(Ljava/lang/Object;)V
                    """.trimIndent(),
                )
            }

            // 2. Append the "Hide chat" row after the stock rows are copied
            // into the dialog's row list (right after the A1g copy call).
            ThreadLongPressDialogBuilderFingerprint.method.apply {
                val copyInsn =
                    instructions.firstOrNull { insn ->
                        insn.opcode == Opcode.INVOKE_STATIC &&
                            methodRefOrNull(insn)?.let { ref ->
                                ref.name == "A1g" &&
                                    ref.parameterTypes == listOf("Ljava/lang/Iterable;", "Ljava/util/Collection;")
                            } == true
                    } ?: throw IllegalStateException("HideChat: row-list copy call not found")
                val copyIndex = copyInsn.location.index
                // invoke-static {vSrc, vList}: the list is the 2nd register.
                val listRegister = fiveRegisters(copyInsn)[1]

                // The dialog controller is the receiver of the A09(List) call
                // that immediately follows the copy.
                val showInsn =
                    instructions
                        .asSequence()
                        .filter { it.location.index > copyIndex }
                        .firstOrNull { insn ->
                            insn.opcode == Opcode.INVOKE_VIRTUAL &&
                                methodRefOrNull(insn)?.let { ref ->
                                    ref.name == "A09" &&
                                        ref.parameterTypes == listOf("Ljava/util/List;")
                                } == true
                        } ?: throw IllegalStateException("HideChat: dialog show call not found")
                val dialogRegister = fiveRegisters(showInsn)[0]

                addInstructions(
                    copyIndex + 1,
                    """
                    invoke-static {v$dialogRegister, v$listRegister}, $INJECTOR_CLASS_NAME->injectHideChatRow(Ljava/lang/Object;Ljava/util/List;)V
                    """.trimIndent(),
                )
            }

            // 3. Inbox list: drop hidden threads as they are deserialized,
            // so they never reach the inbox adapter. Returning null removes
            // the thread; anything else passes through untouched.
            HideChatThreadDeserializerFingerprint.apply {
                method.apply {
                    if (returnType != "V") {
                        instructions
                            .filter { it.opcode == Opcode.RETURN_OBJECT }
                            .map { it.location.index to singleRegister(it) }
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
