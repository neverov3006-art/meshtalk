# Keep kotlinx.serialization models used for the mesh wire protocol
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class com.meshtalk.app.data.model.**$$serializer { *; }
-keepclassmembers class com.meshtalk.app.data.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.meshtalk.app.data.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
