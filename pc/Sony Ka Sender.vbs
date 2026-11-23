' Sony Ka Sender launcher: starts the receiver quietly, then opens the desk window.
Set sh = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")
dir = fso.GetParentFolderName(WScript.ScriptFullName)
sh.CurrentDirectory = dir
' start the receiver hidden (if it is already running it just exits)
sh.Run "cmd /c node """ & dir & "\server.js"" >> """ & dir & "\server.log"" 2>&1", 0, False
WScript.Sleep 1200
edge = sh.ExpandEnvironmentStrings("%ProgramFiles(x86)%") & "\Microsoft\Edge\Application\msedge.exe"
If Not fso.FileExists(edge) Then edge = sh.ExpandEnvironmentStrings("%ProgramFiles%") & "\Microsoft\Edge\Application\msedge.exe"
profile = sh.ExpandEnvironmentStrings("%LOCALAPPDATA%") & "\SonyKaSender\edge-profile"
If fso.FileExists(edge) Then
  sh.Run """" & edge & """ --app=http://localhost:7777/ --user-data-dir=""" & profile & """ --start-maximized --autoplay-policy=no-user-gesture-required --no-first-run", 1, False
Else
  sh.Run "http://localhost:7777/", 1, False
End If
