#!/bin/bash
# usage: run.sh <worktree> <label> ; 1 discarded warm-up run + 5 measured runs, one at a time
set -e
WT=$1; L=$2; OUT=C:/Users/antho/perf-98c61dd1/$L
mkdir -p $OUT; rm -f $OUT/*.txt
cd $WT
for i in 0 1 2 3 4 5; do
  echo "##infantry step $L-run$i"
  ./gradlew.bat :superiorSoftLibrary:infantryUi:runTabShots --max-workers=2 --console=plain -q \
    --init-script C:/Users/antho/perf-98c61dd1/perf.init.gradle -Dperf.out=$OUT/run$i.txt > $OUT/log$i.txt 2>&1 || { echo "run $i failed"; tail -30 $OUT/log$i.txt; exit 1; }
  grep -c "^PERF" $OUT/run$i.txt
done
echo ALLDONE
