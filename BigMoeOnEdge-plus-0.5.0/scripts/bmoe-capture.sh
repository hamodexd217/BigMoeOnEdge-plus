#!/bin/sh
# BigMoeOnEdge A/B capture. Run inside Termux (adb already connected to this phone).
#   1. Start a LONG generation in the app you are testing (about 150 tokens).
#   2. While it is generating, run:   sh bmoe-capture.sh original     (or:  sh bmoe-capture.sh plus)
#   3. Send the file it prints at the end.
# Read-only: it only reads system counters; it changes nothing on the phone.
LABEL="${1:-run}"
OUT="$HOME/bmoe-capture-$LABEL-$(date +%H%M%S).txt"

DEV=$(cat <<'EOF'
vm() { grep -E '^(pswpin|pswpout|pgmajfault|allocstall[a-z_]*|pgscan_kswapd|pgscan_direct|pgsteal_kswapd|pgsteal_direct|workingset_refault[a-z_]*) ' /proc/vmstat; }
ks() { cat /proc/$(pidof kswapd0)/stat 2>/dev/null | cut -d' ' -f14,15; }
echo "== device"; getprop ro.product.model; getprop ro.build.version.release
echo "== meminfo"; grep -E 'MemTotal|MemFree|MemAvailable|SwapTotal|SwapFree' /proc/meminfo
echo "== battery"; dumpsys battery | grep -E 'level|temperature'
echo "== thermal status"; dumpsys thermalservice 2>/dev/null | grep -i status | head -5
echo "== cpu policy: cpu max_hw max_set governor"
for c in /sys/devices/system/cpu/cpu[0-9]*; do echo "$(basename $c) $(cat $c/cpufreq/cpuinfo_max_freq) $(cat $c/cpufreq/scaling_max_freq) $(cat $c/cpufreq/scaling_governor)"; done
echo "== thermal zones (cpu/gpu/skin/battery/soc), millidegrees"
for z in /sys/class/thermal/thermal_zone*; do t=$(cat $z/type); case "$t" in *cpu*|*skin*|*batt*|*gpu*|*soc*) echo "$t $(cat $z/temp)";; esac; done | head -40
echo "== vmstat BEFORE"; vm
echo "kswapd0 utime stime (ticks) BEFORE: $(ks)"
echo "== cpu cur freq kHz, cpu0..cpu7, 1 sample/second for 15 s"
i=0; while [ $i -lt 15 ]; do for c in /sys/devices/system/cpu/cpu[0-9]*; do printf "%s " "$(cat $c/cpufreq/scaling_cur_freq 2>/dev/null)"; done; echo; i=$((i+1)); sleep 1; done
echo "== vmstat AFTER"; vm
echo "kswapd0 utime stime (ticks) AFTER: $(ks)"
echo "== busiest threads"; top -H -b -n 1 -m 15
EOF
)

adb shell "$DEV" > "$OUT" 2>&1
echo "Saved: $OUT"
