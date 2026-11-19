#!/usr/bin/env bash
# Compiles and runs the checks of the logic that does without Android: no emulator, no Gradle,
# no network. Android ships org.json; off device it comes from lib/, kept in the repository
# because downloading it from Maven Central at each run made CI fail whenever Maven answered
# the runner with an error.
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
jar="$root/android/checks/lib/json-20240303.jar"
out=${OUT:-$(mktemp -d)}
src="$root/android/app/src/main/java/fr/fonote"

echo "0ebb88e8fb5122b7506d5cf1d69f1ccdb790d22a  $jar" | sha1sum -c --quiet
javac -cp "$jar" -d "$out" \
    "$src"/{Formation,Lineup,MatchClock,PlayerName,Skin,Logo,Kickoff,Track,Diagram,Sequence,TacticalHistory,Heat}.java \
    "$root"/android/checks/fr/fonote/*Check.java
for check in Formation Lineup MatchClock PlayerName Skin Logo Kickoff Track Sequence Heat; do
    java -ea -cp "$out:$jar" fr.fonote.${check}Check
done
java -ea -cp "$out:$jar" fr.fonote.DiagramCheck json
