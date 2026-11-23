# jiffy: build, flash and test helpers.   Run `just` to list targets.

# Gradle 8.13 can't run on JDK 25: point ANDROID_JAVA_HOME at a JDK 17/21 if your default is newer.
export JAVA_HOME := env_var_or_default("ANDROID_JAVA_HOME", env_var_or_default("JAVA_HOME", ""))
pkg   := "cz.starenka.jiffy"
apk   := "app/build/outputs/apk/debug/app-debug.apk"
port  := "2222"
# With several adb devices, export ANDROID_SERIAL.
# sftp/smoke/mount find the phone via mDNS (the app advertises while sharing; see scripts/phone-ip).
# PHONE_IP=ip overrides it. Looked up only when a recipe runs without an explicit ip.
phone_ip   := justfile_directory() / "scripts/phone-ip"
mnt   := env_var_or_default("MNT", "/tmp/phone")
# Cache aggressively to cut Wi-Fi round trips; changes made elsewhere show up after up to 2 minutes.
# Override with SSHFS_OPTS=... (e.g. SSHFS_OPTS="" for fresh listings).
sshfs_opts := env_var_or_default("SSHFS_OPTS", "dcache_timeout=120,dcache_stat_timeout=120,attr_timeout=120,entry_timeout=120,negative_timeout=30,auto_cache,max_conns=4")

default:
    @just --list

# --- build -----------------------------------------------------------------

# Build the debug APK
[group("build")]
build:
    ./gradlew assembleDebug

# Run the JVM unit tests
[group("build")]
test:
    ./gradlew testDebugUnitTest

# Remove build outputs
[group("build")]
clean:
    ./gradlew clean

# Android lint
[group("build")]
lint:
    ./gradlew lintDebug

# --- device ----------------------------------------------------------------

# Pair over Wi-Fi (Developer options > Wireless debugging > Pair with code): just pair 192.168.0.50:PAIRPORT
[group("setup")]
pair addr:
    adb pair {{addr}}

# Connect wireless adb (ip:port from the Wireless debugging screen)
[group("setup")]
connect addr:
    adb connect {{addr}}

# List adb devices
[group("setup")]
devices:
    adb devices -l

# Install the current debug APK on the device (builds first)
[group("deploy")]
install: build
    adb install -r {{apk}}

# Grant All files access + notifications (no manual settings trips)
[group("deploy")]
grant:
    adb shell appops set {{pkg}} MANAGE_EXTERNAL_STORAGE allow
    adb shell pm grant {{pkg}} android.permission.POST_NOTIFICATIONS

# Open the app on the phone
[group("deploy")]
launch:
    adb shell am start -n {{pkg}}/.MainActivity

# Build, flash, grant permissions and launch
[group("deploy")]
deploy: install grant launch

# Force-stop the app (stops sharing)
[group("deploy")]
stop:
    adb shell am force-stop {{pkg}}

# Remove the app (and its host key + saved keys) from the phone
[group("deploy")]
uninstall:
    adb uninstall {{pkg}}

# Clear app data (new host key, no keys, default shares)
[group("deploy")]
reset:
    adb shell pm clear {{pkg}}

# Follow the app's log output
[group("debug")]
logs:
    adb logcat --pid=$(adb shell pidof {{pkg}}) 

# Log lines from the SFTP service/SSHD only, last run
[group("debug")]
errors:
    adb logcat -d | grep -E "SftpService|AndroidRuntime|org.apache.sshd" | tail -40

# Keep the phone awake while charging (handy during testing); `just stayon false` to undo
[group("debug")]
stayon state="true":
    adb shell svc power stayon {{state}}

# --- test from this machine --------------------------------------------------

# Interactive sftp session
[group("use")]
sftp ip=shell(phone_ip):
    sftp -P {{port}} {{ip}}

# Non-interactive smoke test: list root and Music, then upload/download/delete a temp file
[group("use")]
smoke ip=shell(phone_ip):
    #!/usr/bin/env bash
    set -euo pipefail
    t=$(mktemp -d); echo "smoke $(date -Is)" > "$t/smoke.txt"
    printf 'ls -l\nls -l Music\n' | sftp -P {{port}} -o BatchMode=yes {{ip}} | awk 'NR<=8'
    scp -P {{port}} "$t/smoke.txt" {{ip}}:/Music/.smoke.txt
    scp -P {{port}} {{ip}}:/Music/.smoke.txt "$t/back.txt"
    cmp "$t/smoke.txt" "$t/back.txt" && echo "scp round-trip OK"
    printf 'rm /Music/.smoke.txt\n' | sftp -P {{port}} -o BatchMode=yes {{ip}} >/dev/null
    rm -rf "$t"

# Mount all shares at $MNT; or a single one: just mount 192.168.0.50 Music
[group("use")]
mount ip=shell(phone_ip) share="":
    mkdir -p {{mnt}}
    sshfs -p {{port}} -o reconnect,ServerAliveInterval=15{{ if sshfs_opts == '' { '' } else { ',' + sshfs_opts } }} {{ip}}:/{{share}} {{mnt}}
    @echo "mounted at {{mnt}}"

# Unmount
[group("use")]
umount:
    fusermount3 -u {{mnt}}
