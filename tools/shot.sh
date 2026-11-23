export MSYS_NO_PATHCONV=1
A=/f/Android/sdk/platform-tools/adb.exe
$A shell screencap -p /sdcard/sks_shot.png 2>/dev/null
cd "$TMP/sks" && $A pull /sdcard/sks_shot.png raw.png >/dev/null 2>&1
ffmpeg -v error -y -i raw.png -vf scale=430:-1 "$1.png" && echo "$TMP/sks/$1.png"
