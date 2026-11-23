package app.soredake.patches.qwen

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.smali.ExternalLabel
import app.soredake.patches.shared.Constants.COMPATIBILITY_QWEN
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val CUSTOM_TABS_INTENT = "Landroidx/browser/customtabs/CustomTabsIntent;"
private const val CUSTOM_TABS_BUILDER = "Landroidx/browser/customtabs/CustomTabsIntent\$Builder;"
private const val QWEN_POPUP_HANDLER = "Lai/qwenlm/chat/android/ui/web/WebScreenKt\$openPopupInComposeWeb\$1;"
private const val QWEN_POPUP_CONTEXT_FIELD = "\$context"

private object QwenWebNavigationFingerprint : Fingerprint(
    definingClass = "Lai/qwenlm/chat/android/browser/QwenChatWebViewClient;",
    name = "shouldOverrideUrlLoading",
    returnType = "Z",
    parameters = listOf("Landroid/webkit/WebView;", "Landroid/webkit/WebResourceRequest;"),
    strings = listOf("download=true", "oauth/google/login")
)

private object QwenPopupNavigationFingerprint : Fingerprint(
    definingClass = "Lai/qwenlm/chat/android/ui/web/WebScreenKt\$openPopupInComposeWeb\$1;",
    name = "handleUrl",
    returnType = "Z",
    parameters = listOf("Landroid/webkit/WebView;", "Ljava/lang/String;"),
    strings = listOf("about:blank")
)

private object QwenWebViewActivityFingerprint : Fingerprint(
    definingClass = "Lcom/aliyun/tongyi/browser/QwenWebViewActivity;",
    name = "onCreate",
    returnType = "V",
    parameters = listOf("Landroid/os/Bundle;"),
    strings = listOf("targetUrl")
)

private object ComposeUriHandlerFingerprint : Fingerprint(
    definingClass = "Landroidx/compose/ui/platform/AndroidUriHandler;",
    name = "openUri",
    returnType = "V",
    parameters = listOf("Ljava/lang/String;"),
    strings = listOf("android.intent.action.VIEW")
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

        // Links opened with window.open are routed through this popup handler,
        // which otherwise sends HTTP(S) destinations to QwenWebViewActivity.
        val popupMethod = QwenPopupNavigationFingerprint.method
        val popupInstructions = popupMethod.implementation!!.instructions
        val uriClassifierIndex = popupInstructions.indexOfFirst { instruction ->
            instruction.opcode == Opcode.INVOKE_STATIC &&
                ((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.let { reference ->
                    reference.definingClass == "Lai/qwenlm/chat/android/clean/utils/WebExtKt;" &&
                        reference.name == "g"
                } == true
        }
        require(uriClassifierIndex >= 0) {
            "Could not locate Qwen's popup URL routing check."
        }
        val cleanupIndex = popupInstructions.indexOfFirst { instruction ->
            instruction.opcode == Opcode.INVOKE_VIRTUAL &&
                ((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.let { reference ->
                    reference.definingClass == "Landroid/webkit/WebView;" &&
                        reference.name == "stopLoading"
                } == true
        }
        require(cleanupIndex > uriClassifierIndex) {
            "Could not locate Qwen's popup WebView cleanup path."
        }
        require(popupMethod.implementation!!.registerCount >= 7) {
            "Unexpected register layout in Qwen's popup navigation method."
        }

        popupMethod.addInstructionsWithLabels(
            uriClassifierIndex,
            """
                invoke-static { p2 }, Landroid/net/Uri;->parse(Ljava/lang/String;)Landroid/net/Uri;
                move-result-object v0
                invoke-virtual { v0 }, Landroid/net/Uri;->getScheme()Ljava/lang/String;
                move-result-object v2
                if-eqz v2, :qwen_popup_continue
                const-string v3, "http"
                invoke-virtual { v2, v3 }, Ljava/lang/String;->equalsIgnoreCase(Ljava/lang/String;)Z
                move-result v3
                const-string v0, "https"
                invoke-virtual { v2, v0 }, Ljava/lang/String;->equalsIgnoreCase(Ljava/lang/String;)Z
                move-result v0
                or-int/2addr v3, v0
                if-eqz v3, :qwen_popup_continue
                invoke-static { p2 }, Landroid/net/Uri;->parse(Ljava/lang/String;)Landroid/net/Uri;
                move-result-object v0
                iget-object v2, p0, $QWEN_POPUP_HANDLER->$QWEN_POPUP_CONTEXT_FIELD:Landroid/content/Context;
                new-instance v3, $CUSTOM_TABS_BUILDER
                invoke-direct { v3 }, $CUSTOM_TABS_BUILDER-><init>()V
                invoke-virtual { v3 }, $CUSTOM_TABS_BUILDER->build()$CUSTOM_TABS_INTENT
                move-result-object v3
                invoke-virtual { v3, v2, v0 }, $CUSTOM_TABS_INTENT->launchUrl(Landroid/content/Context;Landroid/net/Uri;)V
                goto :qwen_popup_cleanup
            """,
            ExternalLabel("qwen_popup_continue", popupInstructions[uriClassifierIndex]),
            ExternalLabel("qwen_popup_cleanup", popupInstructions[cleanupIndex])
        )

        // Some chat-link routes launch QwenWebViewActivity directly with a
        // targetUrl extra, bypassing both WebViewClient interception points.
        val activityMethod = QwenWebViewActivityFingerprint.method
        val activityInstructions = activityMethod.implementation!!.instructions
        val targetUrlIndex = activityInstructions.indexOfFirst { instruction ->
            (instruction.opcode == Opcode.CONST_STRING ||
                instruction.opcode == Opcode.CONST_STRING_JUMBO) &&
                ((instruction as? ReferenceInstruction)?.reference as? StringReference)?.string == "targetUrl"
        }
        require(targetUrlIndex >= 0) {
            "Could not locate QwenWebViewActivity's target URL extra."
        }
        val targetUrlResultIndex = (targetUrlIndex + 1 until activityInstructions.size).firstOrNull { index ->
            activityInstructions[index].opcode == Opcode.MOVE_RESULT_OBJECT
        } ?: error("Could not locate QwenWebViewActivity's target URL result.")
        val activityContinuationIndex = targetUrlResultIndex + 1
        require(activityContinuationIndex < activityInstructions.size) {
            "Could not locate QwenWebViewActivity's target URL continuation."
        }
        require(activityMethod.implementation!!.registerCount >= 16) {
            "Unexpected register layout in QwenWebViewActivity.onCreate."
        }

        activityMethod.addInstructionsWithLabels(
            activityContinuationIndex,
            """
                if-eqz p1, :qwen_activity_continue
                invoke-static { p1 }, Landroid/net/Uri;->parse(Ljava/lang/String;)Landroid/net/Uri;
                move-result-object v0
                invoke-virtual { v0 }, Landroid/net/Uri;->getScheme()Ljava/lang/String;
                move-result-object v1
                if-eqz v1, :qwen_activity_continue
                const-string v2, "http"
                invoke-virtual { v1, v2 }, Ljava/lang/String;->equalsIgnoreCase(Ljava/lang/String;)Z
                move-result v2
                const-string v3, "https"
                invoke-virtual { v1, v3 }, Ljava/lang/String;->equalsIgnoreCase(Ljava/lang/String;)Z
                move-result v3
                or-int/2addr v2, v3
                if-eqz v2, :qwen_activity_continue
                new-instance v4, $CUSTOM_TABS_BUILDER
                invoke-direct { v4 }, $CUSTOM_TABS_BUILDER-><init>()V
                invoke-virtual { v4 }, $CUSTOM_TABS_BUILDER->build()$CUSTOM_TABS_INTENT
                move-result-object v4
                invoke-virtual { v4, p0, v0 }, $CUSTOM_TABS_INTENT->launchUrl(Landroid/content/Context;Landroid/net/Uri;)V
                invoke-virtual { p0 }, Landroid/app/Activity;->finish()V
                return-void
            """,
            ExternalLabel("qwen_activity_continue", activityInstructions[activityContinuationIndex])
        )

        // Compose text links use AndroidUriHandler, whose ACTION_VIEW intent
        // resolves to Qwen's in-app browser. Route HTTP(S) through Custom Tabs;
        // preserve normal ACTION_VIEW behavior for other URI schemes.
        val composeUriMethod = ComposeUriHandlerFingerprint.method
        val composeUriInstructions = composeUriMethod.implementation!!.instructions
        val actionViewIndex = composeUriInstructions.indexOfFirst { instruction ->
            (instruction.opcode == Opcode.CONST_STRING ||
                instruction.opcode == Opcode.CONST_STRING_JUMBO) &&
                ((instruction as? ReferenceInstruction)?.reference as? StringReference)?.string ==
                "android.intent.action.VIEW"
        }
        require(actionViewIndex > 0) {
            "Could not locate Compose's URI handler."
        }
        val intentCreationIndex = (0 until actionViewIndex).lastOrNull { index ->
            val instruction = composeUriInstructions[index]
            instruction.opcode == Opcode.NEW_INSTANCE &&
                (instruction as? ReferenceInstruction)?.reference.toString() ==
                "Landroid/content/Intent;"
        } ?: error("Could not locate Compose's ACTION_VIEW intent.")

        composeUriMethod.addInstructions(
            intentCreationIndex,
            """
                iget-object v0, p0, Landroidx/compose/ui/platform/AndroidUriHandler;->context:Landroid/content/Context;
                invoke-static { p1 }, Landroid/net/Uri;->parse(Ljava/lang/String;)Landroid/net/Uri;
                move-result-object v3
                invoke-virtual { v3 }, Landroid/net/Uri;->getScheme()Ljava/lang/String;
                move-result-object v2
                if-eqz v2, :compose_uri_fallback
                const-string v1, "https"
                invoke-virtual { v2, v1 }, Ljava/lang/String;->equalsIgnoreCase(Ljava/lang/String;)Z
                move-result v1
                if-nez v1, :compose_uri_custom_tabs
                const-string v1, "http"
                invoke-virtual { v2, v1 }, Ljava/lang/String;->equalsIgnoreCase(Ljava/lang/String;)Z
                move-result v1
                if-eqz v1, :compose_uri_fallback
                :compose_uri_custom_tabs
                new-instance v1, $CUSTOM_TABS_BUILDER
                invoke-direct { v1 }, $CUSTOM_TABS_BUILDER-><init>()V
                invoke-virtual { v1 }, $CUSTOM_TABS_BUILDER->build()$CUSTOM_TABS_INTENT
                move-result-object v1
                invoke-virtual { v1, v0, v3 }, $CUSTOM_TABS_INTENT->launchUrl(Landroid/content/Context;Landroid/net/Uri;)V
                return-void
                :compose_uri_fallback
                new-instance v1, Landroid/content/Intent;
                const-string v2, "android.intent.action.VIEW"
                invoke-direct { v1, v2, v3 }, Landroid/content/Intent;-><init>(Ljava/lang/String;Landroid/net/Uri;)V
                invoke-virtual { v0, v1 }, Landroid/content/Context;->startActivity(Landroid/content/Intent;)V
                return-void
            """
        )
    }
}
