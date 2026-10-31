package win.solobot.omodash.projection

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import win.solobot.omodash.projection.taskhost.TaskContainerView

/** The stock receiver Activity occupies the tile pane; launcher controls stay available. */
@Composable
fun ProjectionWidget(packageName: String, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    TaskContainerView(packageName, Modifier.fillMaxSize())
}
