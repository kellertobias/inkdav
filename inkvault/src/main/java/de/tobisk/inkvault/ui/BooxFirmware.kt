package de.tobisk.inkvault.ui

import android.os.Build
import android.view.View
import org.lsposed.hiddenapibypass.LSPass

/** BOOX's public SDK reflects into vendor APIs hidden by recent Android releases. */
object BooxFirmware {
    var available = false
        private set
    var diagnostic = "Standard Android display"
        private set
    fun initialize() {
        if (!Build.MANUFACTURER.contains("onyx", true) && !Build.BRAND.contains("onyx", true)) return
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                // App-process-only access to the OEM namespace; no blanket framework exemption.
                check(LSPass.addHiddenApiExemptions("Landroid/onyx/", "Landroid/view/View;->refreshScreen"))
            }
            Class.forName("android.onyx.ViewUpdateHelper").getDeclaredMethod(
                "moveTo",
                View::class.java,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType
            )
            available = true
            diagnostic = "BOOX vendor drawing available"
        } catch (e: Exception) {
            diagnostic = "BOOX vendor drawing unavailable: ${e.javaClass.simpleName}"
        } catch (e: LinkageError) {
            diagnostic = "BOOX vendor drawing unavailable: ${e.javaClass.simpleName}"
        }
    }
}
