@echo off
call mvn clean
rem host platform (windows-x64 on this box)
echo compile x64 server
call mvn -q -o -Pserver-dist -DskipTests package -pl :server-net -am
echo compile x86 server
rem the other five, by naming the profile and switching off the auto-activated one
call mvn -q -o -DskipTests package -P "server-dist,windows-x86,!windows-x64"   -pl :server-net -am
echo compile linux server
call mvn -q -o -DskipTests package -P "server-dist,linux-x64,!windows-x64"     -pl :server-net -am
call mvn -q -o -DskipTests package -P "server-dist,linux-aarch64,!windows-x64" -pl :server-net -am
echo compile macos server
call mvn -q -o -DskipTests package -P "server-dist,macos-x64,!windows-x64"     -pl :server-net -am
call mvn -q -o -DskipTests package -P "server-dist,macos-aarch64,!windows-x64" -pl :server-net -am
echo compile x64 GUI
call mvn -q -Pwindows-x64 -Djpackage.type=APP_IMAGE -DskipTests -Dcheckstyle.skip install