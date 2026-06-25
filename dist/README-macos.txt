Phonalyser - running from the platform JAR (macOS)
==================================================

The .dmg installer bundles a Java runtime; the JAR does not, so you need
Java 17 or newer installed.

1. Install a Java 17+ runtime (e.g. Temurin):  https://adoptium.net/
       Apple Silicon (M1/M2/...):  aarch64 Java  +  phonalyser-<version>-macos.jar
       Intel Mac:                  x64 Java      +  phonalyser-<version>-macos-x64.jar
2. Keep these two files together in the same folder:
       phonalyser-<version>-macos.jar      (or -macos-x64.jar on Intel)
       Phonalyser-macos.sh
3. Make the script executable and run it:
       chmod +x Phonalyser-macos.sh
       ./Phonalyser-macos.sh

Or run it directly (the -XstartOnFirstThread flag is REQUIRED on macOS):
       java -XstartOnFirstThread -jar phonalyser-<version>-macos.jar

Notes
 - macOS SWT will NOT start without -XstartOnFirstThread (the script adds it).
 - The download is unsigned, so Gatekeeper may block it.  If macOS says the file
   "cannot be opened", clear the quarantine flag:
       xattr -dr com.apple.quarantine phonalyser-<version>-macos.jar Phonalyser-macos.sh
   or right-click the script -> Open.
