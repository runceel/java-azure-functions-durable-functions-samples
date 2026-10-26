@echo off
call "%~dp0..\durable-csv-batch\mvnw.cmd" %*
exit /b %ERRORLEVEL%
