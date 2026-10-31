#!/usr/bin/env bash
# شاشة تلفزيونك's settings, for agents: no remote, no menus.
#   optimize/saver.sh show                         current settings
#   optimize/saver.sh cities                       built-in Saudi cities by region
#   optimize/saver.sh city الرس                    a built-in city (Arabic name as listed)
#   optimize/saver.sh city 'اسم' 25.86 43.49       any place: name, latitude, longitude
#   optimize/saver.sh autoloc true                 city, weather and time zone from the connection (true|false);
#                                                  picking a city turns it off
#   optimize/saver.sh mosque URL ['اسم']           adhan, iqama and Jumu'a from the mosque's Mawaqit page
#                                                  (https://mawaqit.net/de/SLUG, or SLUG); `mosque none` = calculated
#   optimize/saver.sh adhan true                   the adhan at each prayer time (true|false; on by default)
#   optimize/saver.sh adhan-voice makki-1          random makki-1 makki-2 madani aqsa ahmad-jalal-yahya voice-7 voice-8
#                                                  fajr-trabulsi  (Dhuhr, Asr, Maghrib, Isha)
#   optimize/saver.sh adhan-fajr off               Fajr: off (the default) or one of the voices
#   optimize/saver.sh adhan-volume 10              the TV volume it plays at (then the volume goes back)
#   optimize/saver.sh adhan-test | adhan-stop      play the adhan now / stop it
#   optimize/saver.sh football KEY                 the next five games from football-data.org (a free key; none = off)
#   optimize/saver.sh adhkar true                  morning (Fajr-09:00) and evening (21:00-24:00) adhkar beside the clock
#   optimize/saver.sh night false                  dim the screensaver 21:00-07:00 (true|false; off by default)
#   optimize/saver.sh reminder 30                  notice at the top right N minutes before each adhan (0 = off)
#   optimize/saver.sh reminder-test                show that notice now
#   optimize/saver.sh find-team الهلال             a club's id (Arabic or English name): id:Name | league
#   optimize/saver.sh teams 136013:Al-Hilal 150643:Al-Zulfi    followed teams in order (up to 10)
#   optimize/saver.sh card prayers                 beside the clock: auto (the four in turn, 30 s) verse prayers forecast matches none
#   optimize/saver.sh home-card none               on the home screen (launcher): same choices, none = off
#   optimize/saver.sh photos 15                    change the photo every N minutes: 5 10 15 30 60 (default 30)
#   optimize/saver.sh idle 10                      the TV starts the screensaver after N idle minutes: 1 2 5 10 15 30
#                                                  (the TV's own timer; left alone unless you run this)
#   optimize/saver.sh over-apps true               the screensaver over apps too after the idle delay, when no key
#                                                  was pressed and nothing plays (true|false; an accessibility service)
#   optimize/saver.sh update                       download the latest screen now
#   optimize/saver.sh app-update                   both apps from the latest release, now (they also do it alone, every 6 h)
# Only ADB can change these (sa.urtv.screensaver/.SaverReceiver needs the shell's DUMP).
set -euo pipefail
cd "$(dirname "$0")/.."
PY="${PY:-optimize/venv/bin/python}"
send() {   # $1 action, $2 extras; prints the receiver's answer (the "data" of am broadcast); exit 1 on "error:"
  local out
  out=$("$PY" -I optimize/tv.py "am broadcast -n sa.urtv.screensaver/.SaverReceiver -a sa.urtv.screensaver.$1 ${2:-}" \
    | sed -n '/data="/,$p' | sed '1s/.*data="//; $s/"$//')
  echo "$out"
  [[ "$out" != error:* ]]
}
quote() {  # one shell-safe argument for the TV's shell
  case "$1" in *"'"*) echo "no apostrophes please: $1" >&2; exit 1 ;; esac
  printf "'%s'" "$1"
}
case "${1:-}" in
  show)   send GET ;;
  cities) send CITIES ;;
  update) send UPDATE ;;
  app-update) send APP_UPDATE ;;
  city)
    [ $# -eq 2 ] || [ $# -eq 4 ] || { echo "usage: optimize/saver.sh city NAME [LAT LON]"; exit 1; }
    extra="--es city $(quote "$2")"
    [ $# -eq 4 ] && extra="$extra --es lat $(quote "$3") --es lon $(quote "$4")"
    send SET "$extra" ;;
  teams)
    shift
    [ $# -gt 0 ] || { echo "usage: optimize/saver.sh teams ID:EnglishName ..."; exit 1; }
    send SET "--es teams $(quote "$(IFS=,; echo "$*")")" ;;
  autoloc)   send SET "--es autoloc $(quote "${2:?usage: optimize/saver.sh autoloc true|false}")" ;;
  mosque)
    [ $# -ge 2 ] || { echo "usage: optimize/saver.sh mosque https://mawaqit.net/de/SLUG ['Arabic name'] | none"; exit 1; }
    extra="--es mosque $(quote "$2")"
    [ $# -ge 3 ] && extra="$extra --es mosque_name $(quote "$3")"
    send SET "$extra" ;;
  adhan)        send SET "--es adhan $(quote "${2:?usage: optimize/saver.sh adhan true|false}")" ;;
  adhan-voice)  send SET "--es adhan_voice $(quote "${2:?usage: optimize/saver.sh adhan-voice makki-1}")" ;;
  adhan-fajr)   send SET "--es adhan_fajr $(quote "${2:?usage: optimize/saver.sh adhan-fajr off|fajr-trabulsi}")" ;;
  adhan-volume) send SET "--es adhan_volume $(quote "${2:?usage: optimize/saver.sh adhan-volume 10}")" ;;
  adhan-test)   send ADHAN_TEST ;;
  football)     send SET "--es fdkey $(quote "${2:?usage: optimize/saver.sh football KEY|none}")" ;;
  adhkar)       send SET "--es adhkar $(quote "${2:?usage: optimize/saver.sh adhkar true|false}")" ;;
  night)        send SET "--es night $(quote "${2:?usage: optimize/saver.sh night true|false}")" ;;
  reminder)     send SET "--es reminder $(quote "${2:?usage: optimize/saver.sh reminder 30}")" ;;
  reminder-test) send REMINDER_TEST ;;
  adhan-stop)   send ADHAN_STOP ;;
  card)      send SET "--es panel $(quote "${2:?usage: optimize/saver.sh card prayers}")" ;;
  home-card) send SET "--es home_panel $(quote "${2:?usage: optimize/saver.sh home-card none}")" ;;
  photos)    send SET "--es photos $(quote "${2:?usage: optimize/saver.sh photos 15}")" ;;
  idle)
    # the app may change the TV's timer only if the owner allowed it; the shell always may
    out=$(send SET "--es idle $(quote "${2:?usage: optimize/saver.sh idle 10}")") || true
    case "$out" in
      "error: idle not allowed"*)
        case "$2" in 1|2|5|10|15|30) ;; *) echo "error: idle is one of 1 2 5 10 15 30"; exit 1 ;; esac
        "$PY" -I optimize/tv.py "settings put system screen_off_timeout $(($2 * 60000))"
        echo "idle: $2 (set from the shell)" ;;
      error:*) echo "$out"; exit 1 ;;
      *) echo "$out" ;;
    esac ;;
  over-apps)   # an accessibility service: the shell adds or removes it in the TV's list (other services kept)
    svc=sa.urtv.screensaver/sa.urtv.screensaver.IdleWatch
    cur=$("$PY" -I optimize/tv.py "settings get secure enabled_accessibility_services" | tr -d '\r' | tail -1)
    [[ "$cur" == null ]] && cur=""
    rest=$(echo "$cur" | tr ':' '\n' | grep -v -i '^sa.urtv.screensaver/' | grep . | paste -sd: - || true)
    case "${2:?usage: optimize/saver.sh over-apps true|false}" in
      true)  new="${rest:+$rest:}$svc" ;;
      false) new="$rest" ;;
      *) echo "error: over-apps true|false"; exit 1 ;;
    esac
    if [[ -n "$new" ]]; then "$PY" -I optimize/tv.py "settings put secure enabled_accessibility_services $new" >/dev/null
    else "$PY" -I optimize/tv.py "settings delete secure enabled_accessibility_services" >/dev/null; fi
    "$PY" -I optimize/tv.py "settings put secure accessibility_enabled $([[ -n "$new" ]] && echo 1 || echo 0)" >/dev/null
    echo "over-apps: $2" ;;
  find-team) shift; "$PY" -I optimize/find-team.py "$@" ;;   # Arabic or English name
  *) sed -n '2,20p' "$0"; exit 1 ;;
esac
