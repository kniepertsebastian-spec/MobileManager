package app.mobilemanager.data.shizuku;

interface IShellService {
    /** Von Shizuku reserviert: beendet den UserService-Prozess. */
    void destroy() = 16777114;

    /** Führt "am force-stop <packageName>" aus. Gibt den Exit-Code zurück. */
    int forceStop(String packageName) = 1;
}
