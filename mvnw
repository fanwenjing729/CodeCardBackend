#!/bin/sh
# Maven Wrapper startup script

MAVEN_PROJECTBASEDIR=$(cd "$(dirname "$0")" && pwd)
export MAVEN_PROJECTBASEDIR

if [ -z "$JAVA_HOME" ]; then
    JAVA_CMD="java"
else
    JAVA_CMD="$JAVA_HOME/bin/java"
fi

WRAPPER_JAR="$MAVEN_PROJECTBASEDIR/.mvn/wrapper/maven-wrapper.jar"
exec "$JAVA_CMD" -jar "$WRAPPER_JAR" "$@"
