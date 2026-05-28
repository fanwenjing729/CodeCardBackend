@echo off
setlocal
set JAVA_HOME=E:\JDK
set MAVEN_HOME=E:\maven\apache-maven-3.9.9
set JWT_SECRET=dEVzdC1zZWNyZXQta2V5LTEyMzQ1Njc4OTAtYWJjZGVmZ2hpamtsbW5vcC1taW4tMjU2LWJpdHM=
set PATH=%JAVA_HOME%\bin;%MAVEN_HOME%\bin;%PATH%

echo ========================================
echo   CodeCard Spring Boot Backend
echo ========================================
echo.

cd /d "%~dp0"
mvn spring-boot:run
pause
