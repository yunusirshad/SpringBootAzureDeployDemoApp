#!/usr/bin/env bash
# Prints today's 9:30 AM ET market-bias call (Bullish / Bearish / Neutral with each factor) and exits.
# Builds the app first if needed. Pass --json for JSON, or --intraday for the hourly session reading
# (10:00 AM to 4:00 PM ET, with whether it flipped from the hour before).
set -euo pipefail
cd "$(dirname "$0")/.."
if [ ! -f target/personal-screener.jar ] || [ -n "$(find src -newer target/personal-screener.jar -print -quit)" ]; then
  mvn -q -B package -DskipTests >/dev/null
fi
exec java -Dloader.main=com.personalscreener.BiasCli -cp target/personal-screener.jar \
  org.springframework.boot.loader.launch.PropertiesLauncher "$@"
