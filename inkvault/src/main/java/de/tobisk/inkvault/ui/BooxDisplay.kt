package de.tobisk.inkvault.ui

import android.os.Build
import android.view.View
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode

/** View-scoped refresh settings, always reset when the document closes or activity stops. */
object BooxDisplay {
    /** One full-panel cleaning refresh; does not change the selected fast/writing mode. */
    fun fullRefresh(view: View): Boolean {
        view.invalidate()
        if (!BooxFirmware.available) return false
        return try {
            // The SDK otherwise silently ignores an unresolved vendor method.
            View::class.java.getDeclaredMethod("refreshScreen", Int::class.javaPrimitiveType)
            EpdController.refreshScreen(view, UpdateMode.GC)
            true
        } catch (_: LinkageError) {
            false
        } catch (_: Exception) {
            false
        }
    }

    fun apply(view: View, mode: String): String {
        if (!Build.MANUFACTURER.contains("onyx", true) && !Build.BRAND.contains("onyx", true)) return "Standard Android display"
        return try {
            if (mode == "System") {
                EpdController.resetViewUpdateMode(view)
                "BOOX display · System default"
            } else {
                val update = when (mode) {
                    "Writing" -> UpdateMode.DU
                    "Regal" -> UpdateMode.REGAL
                    else -> UpdateMode.GU
                }
                if (EpdController.setViewDefaultUpdateMode(view, update)) "BOOX display · $mode" else "BOOX display API unavailable · System default"
            }
        } catch (_: LinkageError) {
            "BOOX SDK unavailable · System default"
        } catch (_: RuntimeException) {
            "BOOX display mode unavailable · System default"
        }
    }
}
