@echo off
del target/installer
mvn clean "-Pwindows-x64" -Djpackage.type=APP_IMAGE -DskipTests package
