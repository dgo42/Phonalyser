Phonalyser - running from the platform JAR (Windows)
====================================================

This is the "bring your own Java" option.  The .exe installer already bundles a
Java runtime; the JAR does not, so you need Java 17 or newer installed.

1. Install a Java 17+ runtime (e.g. Temurin):  https://adoptium.net/
2. Keep these two files together in the same folder:
       phonalyser-<version>-windows.jar
       Phonalyser-windows.bat
3. Double-click  Phonalyser-windows.bat   (or run it from a terminal).

Or run it directly:
       java -jar phonalyser-<version>-windows.jar

Notes
 - The JAR is Windows-specific (it bundles the Windows SWT native).  Use the
   -windows.jar on Windows only.
 - The download is unsigned, so Windows SmartScreen may warn "unknown
   publisher".  Click "More info" -> "Run anyway".
