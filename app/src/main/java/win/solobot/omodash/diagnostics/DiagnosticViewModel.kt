package win.solobot.omodash.diagnostics

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import win.solobot.omodash.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class DiagnosticState(
    val busy: Boolean = false,
    val report: File? = null,
    val message: String? = null,
    val error: String? = null
)

class DiagnosticViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val prefs = app.getSharedPreferences("diagnostic_delivery", Application.MODE_PRIVATE)
    val recipient = prefs.getString("email", BuildConfig.REPORT_EMAIL).orEmpty()
    private val mutable = MutableStateFlow(DiagnosticState(report = DiagnosticReport.latest(app)))
    val state = mutable.asStateFlow()

    fun save(description: String, includeSystem: Boolean) = work {
        val file = prepare(description, includeSystem)
        DiagnosticReport.saveToDownloads(app, file)
        mutable.value = mutable.value.copy(message = "Saved to Downloads/Omodash/${file.name}")
    }

    fun send(description: String, includeSystem: Boolean, email: String) {
        prefs.edit().putString("email", email.trim()).apply()
        work {
            val file = prepare(description, includeSystem)
            withContext(Dispatchers.Main) { DiagnosticReport.share(app, file, email) }
            mutable.value = mutable.value.copy(message = "Choose Gmail and press Send. The ZIP is attached; opening the mail window does not send it automatically.")
        }
    }

    private fun prepare(description: String, includeSystem: Boolean): File {
        val file = DiagnosticReport.create(app, description, includeSystem)
        mutable.value = mutable.value.copy(report = file)
        return file
    }

    private fun work(block: suspend () -> Unit) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, error = null, message = null)
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { block() } }
            catch (error: Exception) { error(error) }
            finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }

    private fun error(error: Throwable) {
        DiagnosticLog.event("Report", "Report operation failed", error)
        mutable.value = mutable.value.copy(error = error.message ?: error.javaClass.simpleName)
    }
}
