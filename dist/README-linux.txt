Phonalyser - running from the platform JAR (Linux)
==================================================

The .deb installer bundles a Java runtime; the JAR does not, so you need
Java 17 or newer installed.

1. Install a Java 17+ runtime:
       Debian/Ubuntu:  sudo apt install openjdk-17-jre
       or Temurin:     https://adoptium.net/
2. Keep these two files together in the same folder:
       phonalyser-<version>-linux.jar
       Phonalyser-linux.sh
3. Make the script executable and run it:
       chmod +x Phonalyser-linux.sh
       ./Phonalyser-linux.sh

Or run it directly:
       java -jar phonalyser-<version>-linux.jar

Notes
 - The JAR is Linux-specific (it bundles the Linux/GTK SWT native).  Use the
   -linux.jar on Linux only; a GTK 3 desktop is required.
