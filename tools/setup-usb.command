#!/bin/zsh
cd -- "${0:A:h}" || exit 1
python3 vehicle_usb.py "$@"
result=$?
echo
read '?Нажмите Enter, чтобы закрыть окно… '
exit "$result"
