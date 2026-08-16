@echo off
call mvn clean
rem All six server bundles come out of ONE build: the fat jar is universal and
rem the zips differ only in files already in the checkout, so there is nothing
rem left to build per platform.
echo compile the six server bundles
call mvn -q -o -Pserver-dist -DskipTests package -pl :server-net -am
echo compile x64 GUI
call mvn -q -Pwindows-x64 -Djpackage.type=APP_IMAGE -DskipTests -Dcheckstyle.skip install
