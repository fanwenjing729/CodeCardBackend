@REM Maven Wrapper startup script for Windows

@if "%DEBUG%"=="" @echo off
set MAVEN_PROJECTBASEDIR=%CD%
set MAVEN_JAVA_EXE=java.exe
%MAVEN_JAVA_EXE% -version >NUL 2>&1
if %ERRORLEVEL% neq 0 (
    if defined JAVA_HOME set MAVEN_JAVA_EXE=%JAVA_HOME%\bin\java.exe
)
%MAVEN_JAVA_EXE% -version >NUL 2>&1
if %ERRORLEVEL% neq 0 (
    echo ERROR: JAVA_HOME is not set and no 'java' command could be found.
    exit /b 1
)

set WRAPPER_JAR=.mvn\wrapper\maven-wrapper.jar
java -jar "%WRAPPER_JAR%" %*
