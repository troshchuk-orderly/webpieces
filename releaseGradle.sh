#!/bin/bash

DIR=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )

cd $DIR
./gradlew -PprojVersion=2 :webserver:gradle-plugin-htmlcompiler:uploadArchives