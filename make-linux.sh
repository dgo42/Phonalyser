#!/bin/bash
cd deps/flac-library-java
mvn clean install
cd ../..
rm -rf target/installer
mvn clean "-Plinux-x64" -Djpackage.type=APP_IMAGE -DskipTests package
