// Top-level build file.
//
// Plugins are intentionally NOT declared here with `apply false`: the project
// supports a JVM-only mode (-Pvboard.skipAndroid=true) for environments without
// Android SDK / Google Maven access, and declaring the Android Gradle Plugin at
// the root would force its resolution even when :app is excluded. Each module
// declares its own plugins via the version catalog instead.
