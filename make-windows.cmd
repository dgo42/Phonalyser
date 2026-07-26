@echo off
cd deps\flac-library-java
mvn clean install
cd ..\..
del target/installer
mvn clean "-Pwindows-x64" -Djpackage.type=APP_IMAGE -DskipTests package
