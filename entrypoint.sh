#!/bin/sh
if [ "$APP" = "tracker" ]; then
  exec tracker/bin/online-tracker
else
  exec bin/alt-finder
fi
