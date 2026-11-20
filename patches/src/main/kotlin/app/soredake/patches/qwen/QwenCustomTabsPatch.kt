package app.soredake.patches.qwen

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.getFreeRegisterProvider
import app.soredake.patches.shared.Constants.COMPATIBILITY_QWEN
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

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
        val downloadCheck = instructions.indexOfLast {
            (it.opcode == Opcode.CONST_STRING || it.opcode == Opcode.CONST_STRING_JUMBO) &&
                it.toString().contains("\"download=true\"")
        }
        require(downloadCheck >= 0) { "Could not locate Qwen's download navigation check." }

        // Locate the false return after download/OAuth handling; earlier returns
        // implement those existing interceptions and must remain untouched.
        val falseConstantIndex = (downloadCheck + 1 until instructions.size - 1).firstOrNull { index ->
            val constant = instructions[index]
            val returned = instructions[index + 1]
            constant.opcode == Opcode.CONST_4 &&
                constant is NarrowLiteralInstruction && constant.narrowLiteral == 0 &&
                constant is OneRegisterInstruction && returned.opcode == Opcode.RETURN &&
                returned is OneRegisterInstruction && constant.registerA == returned.registerA
        } ?: error("Could not locate the normal WebView return-false path.")

        val returnRegister = (instructions[falseConstantIndex] as OneRegisterInstruction).registerA
        val registers = method.getFreeRegisterProvider(falseConstantIndex, 3)
        val freeContext = registers.getFreeRegister4Bit()
        val freeUri = registers.getFreeRegister4Bit()
        val freeTabs = registers.getFreeRegister4Bit()

        method.addInstructions(
            falseConstantIndex,
            """
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
                return v$returnRegister
            """
        )
    }
}
