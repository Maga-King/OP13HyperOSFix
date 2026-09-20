# Hook entry points and callback method names are resolved outside the APK.
-keep class local.mio.op13hyperosfix.** { *; }
-keep class local.mio.coloroswalletcompat.** { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-dontwarn de.robv.android.xposed.**
