@echo off
setlocal
set DIR=%~dp0
set JAVA_BIN=%JAVA_HOME%\bin\java.exe
if not exist "%JAVA_BIN%" set JAVA_BIN=java
"%JAVA_BIN%" -classpath "%DIR%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*