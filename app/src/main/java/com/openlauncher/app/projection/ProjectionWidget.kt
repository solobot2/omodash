package com.openlauncher.app.projection

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import com.openlauncher.app.projection.taskhost.TaskContainerView

/** The stock receiver Activity occupies the tile pane; launcher controls stay available. */
@Composable
fun ProjectionWidget(packageName: String, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    TaskContainerView(packageName, Modifier.fillMaxSize())
}
