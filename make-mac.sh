#!/bin/bash
rm -rf target/installer
mvn clean "-Pmacos-x64" -Djpackage.type=APP_IMAGE -DskipTests package
