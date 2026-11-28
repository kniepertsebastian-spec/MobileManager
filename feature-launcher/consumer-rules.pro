# Launcher-Modul: R8-Regeln, die mit in die App wandern (consumerProguardFiles).
# Enums (Profile, Sortierung, Gesten …) werden per Name in Room/DataStore gespeichert –
# valueOf()/values() müssen auch in verkleinerten Builds erhalten bleiben.
-keepclassmembers enum app.mobilemanager.launcher.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
