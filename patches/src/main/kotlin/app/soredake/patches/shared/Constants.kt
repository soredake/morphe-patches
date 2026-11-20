package app.soredake.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_QWEN = Compatibility(
        name = "Qwen",
        packageName = "com.tongyi.intl",
        apkFileType = ApkFileType.APK,
        appIconColor = 0xFF7A3E,
        targets = listOf(AppTarget(version = "2.5.1"))
    )
}
