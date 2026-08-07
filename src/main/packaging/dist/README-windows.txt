Phonalyser - running from the platform JAR (Windows)
====================================================

This is the "bring your own Java" option.  The .exe installer already bundles a
Java runtime; the JAR does not, so you need Java 17 or newer installed.

1. Install a Java 17+ runtime.
2. Keep these two files together in the same folder:
       phonalyser-<version>-windows.jar
       Phonalyser-windows.bat
3. Double-click  Phonalyser-windows.bat   (or run it from a terminal).

Or run it directly:
       java -jar phonalyser-<version>-windows.jar

Notes
 - The JAR is Windows-specific (it bundles the Windows SWT native).  Use the
   -windows.jar on Windows only.
 - On first start a csjsound_*.dll appears next to the JAR - that is the
   bundled 24-bit exclusive-mode JavaSound driver staging itself (it must sit
   in the start directory to be loadable).  Keep it with the JAR; it is
   refreshed automatically.  It also needs the Microsoft Visual C++ runtime
   (vc_redist) matching the JVM: x64 for 64-bit Java, x86 for 32-bit Java.
 - 32-bit Windows: use the 32-bit JAR (phonalyser-<version>-windows-x86.jar)
   and give the JVM an explicit heap cap - a 32-bit Java defaults to a 256 MB
   heap and tops out around 1.4 GB:
       java -Xmx1200m -jar phonalyser-<version>-windows-x86.jar
   (The .bat launcher adds the flag automatically when it picks the x86 JAR.)
 - The download is unsigned, so Windows SmartScreen may warn "unknown
   publisher".  Click "More info" -> "Run anyway".
