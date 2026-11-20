package app.soredake.patches.qwen

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.patch
import app.soredake.patches.shared.Constants.COMPATIBILITY_QWEN
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val CUSTOM_TABS_INTENT = "Landroidx/browser/customtabs/CustomTabsIntent;"
private const val CUSTOM_TABS_BUILDER = "Landroidx/browser/customtabs/CustomTabsIntent\$Builder;"

private object QwenWebNavigationFingerprint : Fingerprint(
    definingClass = "Lai/qwenlm/chat/android/browser/QwenChatWebViewClient;",
    name = "shouldOverrideUrlLoading",
    returnType = "Z",
    parameters = listOf("Landroid/webkit/WebView;", "Landroid/webkit/WebResourceRequest;"),
    strings = listOf("download=true", "oauth/google/login")
)

@Suppress("unused")
val qwenCustomTabsPatch = patch(
    name = "Open links in Custom Tabs",
    description = "Opens Qwen WebView HTTP(S) navigations in Android Custom Tabs.",
    default = true
) {
    compatibleWith(COMPATIBILITY_QWEN)

    execute {
        val method = QwenWebNavigationFingerprint.method
        val instructions = method.implementation!!.instructions
        val downloadCheck = instructions.indexOfLast { instruction ->
            (instruction.opcode == Opcode.CONST_STRING ||
                instruction.opcode == Opcode.CONST_STRING_JUMBO) &&
                ((instruction as? ReferenceInstruction)?.reference as? StringReference)?.string ==
                "download=true"
        }
        require(downloadCheck >= 0) { "Could not locate Qwen's download navigation check." }

        // The ordinary WebView path reaches a shared return-false instruction
        // through a label/goto, so the false constant is not adjacent to RETURN.
        val returnIndex = instructions.indexOfLast { instruction ->
            instruction.opcode == Opcode.RETURN && instruction is OneRegisterInstruction
        }
        require(returnIndex > downloadCheck) {
            "Could not locate the normal WebView return-false path."
        }

        val returnRegister = (instructions[returnIndex] as OneRegisterInstruction).registerA
        val implementation = method.implementation!!
        // Qwen 2.5.1 has ten local registers (v0-v9) and three incoming
        // registers (p0-p2 = v10-v12). Use dedicated locals rather than the
        // free-register allocator, which can mistake incoming parameters as
        // dead after their original last use.
        require(implementation.registerCount >= 13) {
            "Unexpected register layout in Qwen's WebView navigation method."
        }
        val freeGesture = 5
        val freeUri = 6
        val freeContext = 7
        val freeTabs = 8

        method.addInstructions(
            returnIndex,
            """
                invoke-interface { p2 }, Landroid/webkit/WebResourceRequest;->isForMainFrame()Z
                move-result v$freeGesture
                if-eqz v$freeGesture, :qwen_custom_tabs_continue
                invoke-interface { p2 }, Landroid/webkit/WebResourceRequest;->hasGesture()Z
                move-result v$freeGesture
                if-eqz v$freeGesture, :qwen_custom_tabs_continue
                invoke-interface { p2 }, Landroid/webkit/WebResourceRequest;->getUrl()Landroid/net/Uri;
                move-result-object v$freeUri
                invoke-virtual { p1 }, Landroid/view/View;->getContext()Landroid/content/Context;
                move-result-object v$freeContext
                new-instance v$freeTabs, $CUSTOM_TABS_BUILDER
                invoke-direct { v$freeTabs }, $CUSTOM_TABS_BUILDER-><init>()V
                invoke-virtual { v$freeTabs }, $CUSTOM_TABS_BUILDER->build()$CUSTOM_TABS_INTENT
                move-result-object v$freeTabs
                invoke-virtual { v$freeTabs, v$freeContext, v$freeUri }, $CUSTOM_TABS_INTENT->launchUrl(Landroid/content/Context;Landroid/net/Uri;)V
                const/4 v$returnRegister, 0x1
                :qwen_custom_tabs_continue
            """
        )
    }
}
