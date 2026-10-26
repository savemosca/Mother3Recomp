#!/usr/bin/env bash
# Idle frame-pacing benchmark on a connected Android handheld (written for
# the Anbernic RG556): resumes the same suspend state with the same config,
# plays N seconds without input, backgrounds the game and prints its
# session_health line (late_frames = present gaps > 25 ms). Used to compare
# builds installed under separate application ids, so the player's own app
# and save are never touched.
#
# Needs adb, root on the device (su) and an unlocked screen. The state comes
# from a device-side directory holding a .sav, .suspend.state,
# .suspend.state.ra and .suspend.pending (any file stem: copies from older
# builds named mother3_en.* work too); the target app's files are
# overwritten. The ROM and BIOS must already be in the target app.
#
# Usage: [WIDE=1] tools/rg556-idle-bench.sh <package> [seconds] [state-dir] [extra config.ini text]
set -u

PKG="$1"
SECS="${2:-60}"
SRC="${3:-/data/local/tmp/m3-bench-state}"
EXTRA="${4:-}"
WIDE="${WIDE:-0}"  # m3_widescreen value
D="/data/data/$PKG/files"
STEM="mother3_en_v13"

# Taps the on-screen button labelled $1, scrolling the setup page down until
# it shows up.
tap_button() {
  local bounds i
  for ((i = 0; i < 5; i++)); do
    adb shell uiautomator dump /sdcard/bench-ui.xml >/dev/null 2>&1
    bounds=$(adb exec-out cat /sdcard/bench-ui.xml |
      grep -o "text=\"$1\"[^>]*bounds=\"\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]\"" |
      grep -o '\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]' | head -1)
    if [[ -n "$bounds" ]]; then
      read -r x1 y1 x2 y2 <<< "$(tr '[],' '   ' <<< "$bounds")"
      adb shell input tap $(((x1 + x2) / 2)) $(((y1 + y2) / 2))
      adb shell rm -f /sdcard/bench-ui.xml
      return 0
    fi
    adb shell input swipe 960 950 960 250 300
  done
  adb shell rm -f /sdcard/bench-ui.xml
  return 1
}

adb shell am force-stop "$PKG"
adb shell su -c "'set -e; mkdir -p $D/roms; rm -f $D/roms/$STEM.suspend.* $D/android-runtime.log $D/android-exit-code;
  for f in $SRC/*.sav $SRC/*.suspend.state $SRC/*.suspend.state.ra $SRC/*.suspend.pending; do
    n=\$(basename \$f); cp \$f $D/roms/$STEM.\${n#*.}; done;
  printf \"[Display]\\ninteger_scale = 1\\n\\n[Audio]\\nenhanced = 1\\n\\n[Launcher]\\nm3_hq_audio = 0\\nm3_widescreen = $WIDE\\n$EXTRA\" > $D/config.ini;
  U=\$(stat -c %u /data/data/$PKG); C=\$(ls -Zd /data/data/$PKG | cut -d\" \" -f1);
  chown -R \$U:\$U $D; chcon -R \$C $D'"
adb shell svc power stayon usb
adb shell am start -n "$PKG/com.ylports.mother3recomp.Mother3SetupActivity" >/dev/null
sleep 3
# START plays from the home screen; setup pages without it get PLAY tapped.
adb shell input keyevent BUTTON_START
for ((i = 0; ; i++)); do
  adb shell su -c "'grep -q suspend_resumed $D/android-runtime.log 2>/dev/null'" && break
  if ((i == 8)) && ! tap_button PLAY; then
    echo "neither START nor PLAY started the game (ROM or BIOS missing?)" >&2
    adb shell am force-stop "$PKG"; adb shell svc power stayon false; exit 1
  fi
  if ((i >= 60)); then
    echo "the game did not resume within 60 s" >&2
    adb shell am force-stop "$PKG"; adb shell svc power stayon false; exit 1
  fi
  sleep 1
done
sleep "$SECS"
adb shell input keyevent HOME
until adb shell su -c "'grep -q session_health $D/android-runtime.log'"; do sleep 1; done
adb shell su -c "'grep -E \"suspend_resumed|audio=bridge|session_health\" $D/android-runtime.log'"
adb shell am force-stop "$PKG"
adb shell svc power stayon false
