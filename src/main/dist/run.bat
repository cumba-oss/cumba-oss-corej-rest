@echo off
REM Convenience launcher for the coreJ REST distribution.
REM %~dp0 expands to this script's directory (with a trailing backslash), so the
REM bundle is fully relocatable.
REM Set JAVA_OPTS for JVM options, e.g.  set JAVA_OPTS=-Xmx8g
java %JAVA_OPTS% -jar "%~dp0cumba-oss-corej-rest.jar" %*
