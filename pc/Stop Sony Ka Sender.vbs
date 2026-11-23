' Stops the Sony Ka Sender receiver (only the node process running server.js).
Set wmi = GetObject("winmgmts:\\.\root\cimv2")
For Each p In wmi.ExecQuery("SELECT * FROM Win32_Process WHERE Name='node.exe'")
  If InStr(LCase(p.CommandLine), "sonykasender") > 0 And InStr(LCase(p.CommandLine), "server.js") > 0 Then p.Terminate()
Next
MsgBox "Sony Ka Sender stopped.", 64, "Sony Ka Sender"
