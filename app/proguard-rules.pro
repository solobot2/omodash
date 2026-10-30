-keepattributes *Annotation*
-keep class com.openlauncher.app.data.** { *; }
-keep class com.openlauncher.app.model.** { *; }
# app_process resolves this entry point by its literal class name from the signed APK.
-keep class com.openlauncher.app.projection.taskhost.TaskHostShell { *; }
-keep class com.openlauncher.app.projection.taskhost.TaskHostShell$* { *; }
