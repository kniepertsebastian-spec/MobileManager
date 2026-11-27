# ---------------------------------------------------------------------------
# MobileManager – R8-Regeln (Phase 4.0)
#
# Release-Builds werden verkleinert und optimiert, aber nicht verschleiert:
# Stacktraces aus dem Diagnose-Export bleiben lesbar, und Klassennamen, die per
# Name aufgelöst werden (WorkManager, Shizuku, Jakarta Mail), bleiben stabil.
# Room, Hilt, WorkManager, DataStore, OkHttp, Coil und ML Kit bringen eigene
# Consumer-Regeln mit; hier steht nur, was darüber hinaus nötig ist.
# ---------------------------------------------------------------------------
-dontobfuscate
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod

# --- Shizuku -----------------------------------------------------------------
# Der UserService wird von Shizuku per Reflection über den parameterlosen Konstruktor erzeugt,
# die AIDL-Schnittstelle über ihren Deskriptor (Klassenname) angesprochen.
-keep class app.mobilemanager.data.shizuku.ShellService { *; }
-keep class app.mobilemanager.data.shizuku.IShellService { *; }
-keep class app.mobilemanager.data.shizuku.IShellService$* { *; }
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.server.** { *; }

# --- Jakarta Mail / Angus (SMTP-Alarm) ----------------------------------------
# Provider, Transports und DataHandler werden über META-INF/javamail.* und ServiceLoader gefunden.
-keep class org.eclipse.angus.mail.** { *; }
-keep class org.eclipse.angus.activation.** { *; }
-keep class jakarta.mail.** { *; }
-keep class jakarta.activation.** { *; }
-dontwarn java.awt.**
-dontwarn javax.security.sasl.**
-dontwarn javax.naming.**
-dontwarn org.ietf.jgss.**
-dontwarn org.eclipse.angus.mail.**
-dontwarn jakarta.mail.**
-dontwarn jakarta.activation.**
-dontwarn org.graalvm.nativeimage.**

# --- Gesichtserkennung (Cleaner) ----------------------------------------------
# ML Kit lädt Detektor-Implementierungen über Komponenten-Registrare.
-keep class com.google.mlkit.vision.face.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_face** { *; }

# --- Eigene Modelle -----------------------------------------------------------
# Enums werden per Name in Room/DataStore/Backups gespeichert.
-keepclassmembers enum app.mobilemanager.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# --- OkHttp (optionale Plattform-Klassen) ---------------------------------------
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
