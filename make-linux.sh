#!/bin/bash
rm -rf target/installer
mvn clean "-Plinux-x64" -Djpackage.type=APP_IMAGE -DskipTests package
