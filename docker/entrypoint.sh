#!/usr/bin/env sh
set -eu

MAIN_CLASS="uk.co.bithatch.opensim.spawner.OpensimSpawnerApplication"
CLASSPATH="/opt/opensim-spawner/app.jar:/opt/opensim-spawner/lib/*"

if [ -n "${JAVA_OPTS:-}" ]; then
  exec java ${JAVA_OPTS} --enable-native-access=ALL-UNNAMED -cp "${CLASSPATH}" ${MAIN_CLASS} "$@"
else
  exec java -cp "${CLASSPATH}" ${MAIN_CLASS} "$@"
fi

