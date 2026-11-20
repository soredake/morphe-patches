package app.soredake.patches.qwen

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.smali.ExternalLabel
import app.soredake.patches.shared.Constants.COMPATIBILITY_QWEN
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
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

        // Qwen short-circuits handled navigations immediately after calling
        // onNavigationRequest. Intercept that branch before it returns to Qwen's
        // internal browser; later in the method p2 is reused as a Boolean.
        val navigationHandlerIndex = instructions.indexOfLast { instruction ->
            instruction.opcode == Opcode.IGET_OBJECT &&
                ((instruction as? ReferenceInstruction)?.reference as? FieldReference)?.name ==
                "onNavigationRequest"
        }
        require(navigationHandlerIndex in 0 until downloadCheck) {
            "Could not locate Qwen's navigation handler."
        }
        val handledNavigationBranch = (navigationHandlerIndex + 1 until downloadCheck).firstOrNull { index ->
            val instruction = instructions[index]
            val previous = instructions[index - 1]
            (instruction.opcode == Opcode.GOTO ||
                instruction.opcode == Opcode.GOTO_16 ||
                instruction.opcode == Opcode.GOTO_32) &&
            previous.opcode == Opcode.IF_EQZ
        } ?: error("Could not locate Qwen's handled-navigation branch.")
        val continuationInstruction = instructions[handledNavigationBranch]

        val implementation = method.implementation!!
        // Qwen 2.5.1 has ten local registers (v0-v9) and three incoming
        // registers (p0-p2 = v10-v12). This branch returns immediately after
        // the original goto, so these scratch locals are safe to reuse.
        require(implementation.registerCount >= 13) {
            "Unexpected register layout in Qwen's WebView navigation method."
        }
        val freeResult = 5
        val freeUri = 6
        val freeContext = 7
        val freeTabs = 8

        method.addInstructionsWithLabels(
            handledNavigationBranch,
            """
                invoke-interface { p2 }, Landroid/webkit/WebResourceRequest;->getUrl()Landroid/net/Uri;
                move-result-object v$freeUri
                invoke-virtual { v$freeUri }, Landroid/net/Uri;->getScheme()Ljava/lang/String;
                move-result-object v$freeContext
                if-eqz v$freeContext, :qwen_custom_tabs_continue
                const-string v$freeTabs, "http"
                invoke-virtual { v$freeContext, v$freeTabs }, Ljava/lang/String;->equalsIgnoreCase(Ljava/lang/String;)Z
                move-result v$freeTabs
                const-string v9, "https"
                invoke-virtual { v$freeContext, v9 }, Ljava/lang/String;->equalsIgnoreCase(Ljava/lang/String;)Z
                move-result v9
                or-int/2addr v$freeTabs, v9
                if-eqz v$freeTabs, :qwen_custom_tabs_continue
                invoke-virtual { p1 }, Landroid/view/View;->getContext()Landroid/content/Context;
                move-result-object v$freeContext
                new-instance v$freeTabs, $CUSTOM_TABS_BUILDER
                invoke-direct { v$freeTabs }, $CUSTOM_TABS_BUILDER-><init>()V
                invoke-virtual { v$freeTabs }, $CUSTOM_TABS_BUILDER->build()$CUSTOM_TABS_INTENT
                move-result-object v$freeTabs
                invoke-virtual { v$freeTabs, v$freeContext, v$freeUri }, $CUSTOM_TABS_INTENT->launchUrl(Landroid/content/Context;Landroid/net/Uri;)V
                const/4 v$freeResult, 0x1
                return v$freeResult
            """,
            ExternalLabel("qwen_custom_tabs_continue", continuationInstruction)
        )
    }
}
