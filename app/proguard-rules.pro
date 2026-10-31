-keepattributes *Annotation*
-keep class win.solobot.omodash.data.** { *; }
-keep class win.solobot.omodash.model.** { *; }
# app_process resolves this entry point by its literal class name from the signed APK.
-keep class win.solobot.omodash.projection.taskhost.TaskHostShell { *; }
-keep class win.solobot.omodash.projection.taskhost.TaskHostShell$* { *; }
