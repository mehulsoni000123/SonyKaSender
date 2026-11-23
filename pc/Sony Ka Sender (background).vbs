' Starts only the Sony Ka Sender receiver, hidden (used at Windows startup).
' Open the desk window any time with the "Sony Ka Sender" shortcut.
Set sh = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")
dir = fso.GetParentFolderName(WScript.ScriptFullName)
sh.CurrentDirectory = dir
sh.Run "cmd /c node """ & dir & "\server.js"" >> """ & dir & "\server.log"" 2>&1", 0, False
