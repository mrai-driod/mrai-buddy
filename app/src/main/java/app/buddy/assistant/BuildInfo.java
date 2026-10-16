package app.buddy.assistant;

/** Single source of the app version; read by build.sh and app/build.gradle.kts. */
final class BuildInfo {
    static final String VERSION = "0.0.2";
    static final int VERSION_CODE = 2;

    private BuildInfo() {
    }
}
