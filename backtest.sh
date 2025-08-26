#!/bin/bash

# January
java -jar -Dserver.port=9001 -Djforex.system.back-test.start=2015-12-27 target/trading-0.5.0-SNAPSHOT.jar &
java -jar -Dserver.port=9002 -Djforex.system.back-test.start=2016-01-03 target/trading-0.5.0-SNAPSHOT.jar &
java -jar -Dserver.port=9003 -Djforex.system.back-test.start=2016-01-10 target/trading-0.5.0-SNAPSHOT.jar &
java -jar -Dserver.port=9004 -Djforex.system.back-test.start=2016-01-17 target/trading-0.5.0-SNAPSHOT.jar &
java -jar -Dserver.port=9005 -Djforex.system.back-test.start=2016-01-24 target/trading-0.5.0-SNAPSHOT.jar &

# February
#java -jar -Dserver.port=9001 -Djforex.system.back-test.start=2016-01-31 target/trading-0.5.0-SNAPSHOT.jar &
#java -jar -Dserver.port=9002 -Djforex.system.back-test.start=2016-02-07 target/trading-0.5.0-SNAPSHOT.jar &
#java -jar -Dserver.port=9003 -Djforex.system.back-test.start=2016-02-14 target/trading-0.5.0-SNAPSHOT.jar &
#java -jar -Dserver.port=9004 -Djforex.system.back-test.start=2016-02-21 target/trading-0.5.0-SNAPSHOT.jar &
#java -jar -Dserver.port=9005 -Djforex.system.back-test.start=2016-02-28 target/trading-0.5.0-SNAPSHOT.jar &

# March
#java -jar -Dserver.port=9001 -Djforex.system.back-test.start=2016-03-06 target/trading-0.5.0-SNAPSHOT.jar &
#java -jar -Dserver.port=9002 -Djforex.system.back-test.start=2016-03-13 target/trading-0.5.0-SNAPSHOT.jar &
#java -jar -Dserver.port=9003 -Djforex.system.back-test.start=2016-03-20 target/trading-0.5.0-SNAPSHOT.jar &
#java -jar -Dserver.port=9004 -Djforex.system.back-test.start=2016-03-27 target/trading-0.5.0-SNAPSHOT.jar &

# April
#java -jar -Dserver.port=9001 -Djforex.system.back-test.start=2016-04-03 target/trading-0.5.0-SNAPSHOT.jar &
#java -jar -Dserver.port=9002 -Djforex.system.back-test.start=2016-04-10 target/trading-0.5.0-SNAPSHOT.jar &
#java -jar -Dserver.port=9003 -Djforex.system.back-test.start=2016-04-17 target/trading-0.5.0-SNAPSHOT.jar &
#java -jar -Dserver.port=9004 -Djforex.system.back-test.start=2016-04-24 target/trading-0.5.0-SNAPSHOT.jar &

# May
#java -jar -Dserver.port=9001 -Djforex.system.back-test.start=2016-05-01 target/trading-0.5.0-SNAPSHOT.jar &
#java -jar -Dserver.port=9002 -Djforex.system.back-test.start=2016-05-08 target/trading-0.5.0-SNAPSHOT.jar &
#java -jar -Dserver.port=9003 -Djforex.system.back-test.start=2016-05-15 target/trading-0.5.0-SNAPSHOT.jar &
#java -jar -Dserver.port=9004 -Djforex.system.back-test.start=2016-05-22 target/trading-0.5.0-SNAPSHOT.jar &
#java -jar -Dserver.port=9005 -Djforex.system.back-test.start=2016-05-29 target/trading-0.5.0-SNAPSHOT.jar &

# June
#java -jar -Dserver.port=9001 -Djforex.system.back-test.start=2016-06-05 target/trading-0.5.0-SNAPSHOT.jar &
#java -jar -Dserver.port=9002 -Djforex.system.back-test.start=2016-06-12 target/trading-0.5.0-SNAPSHOT.jar &
#java -jar -Dserver.port=9003 -Djforex.system.back-test.start=2016-06-19 target/trading-0.5.0-SNAPSHOT.jar &
#java -jar -Dserver.port=9004 -Djforex.system.back-test.start=2016-06-26 target/trading-0.5.0-SNAPSHOT.jar &

# July
#java -jar -Dserver.port=9001 -Djforex.system.back-test.start=2016-07-03 target/trading-0.5.0-SNAPSHOT.jar &
#java -jar -Dserver.port=9002 -Djforex.system.back-test.start=2016-07-10 target/trading-0.5.0-SNAPSHOT.jar &
#java -jar -Dserver.port=9003 -Djforex.system.back-test.start=2016-07-17 target/trading-0.5.0-SNAPSHOT.jar &
#java -jar -Dserver.port=9004 -Djforex.system.back-test.start=2016-07-24 target/trading-0.5.0-SNAPSHOT.jar &

# August
#java -jar -Dserver.port=9001 -Djforex.system.back-test.start=2016-07-31 target/trading-0.5.0-SNAPSHOT.jar &
#java -jar -Dserver.port=9002 -Djforex.system.back-test.start=2016-08-07 target/trading-0.5.0-SNAPSHOT.jar &
#java -jar -Dserver.port=9003 -Djforex.system.back-test.start=2016-08-14 target/trading-0.5.0-SNAPSHOT.jar &
#java -jar -Dserver.port=9004 -Djforex.system.back-test.start=2016-08-21 target/trading-0.5.0-SNAPSHOT.jar &
#java -jar -Dserver.port=9005 -Djforex.system.back-test.start=2016-08-28 target/trading-0.5.0-SNAPSHOT.jar &

# September
#java -jar -Dserver.port=9001 -Djforex.system.back-test.start=2016-09-04 target/trading-0.5.0-SNAPSHOT.jar &
#java -jar -Dserver.port=9002 -Djforex.system.back-test.start=2016-09-11 target/trading-0.5.0-SNAPSHOT.jar &
#java -jar -Dserver.port=9003 -Djforex.system.back-test.start=2016-09-18 target/trading-0.5.0-SNAPSHOT.jar &
#java -jar -Dserver.port=9004 -Djforex.system.back-test.start=2016-09-25 target/trading-0.5.0-SNAPSHOT.jar &
#java -jar -Dserver.port=9005 -Djforex.system.back-test.start=2016-09-29 target/trading-0.5.0-SNAPSHOT.jar &

# October
#java -jar -Dserver.port=9001 -Djforex.system.back-test.start=2016-10-02 target/trading-0.5.0-SNAPSHOT.jar &
#java -jar -Dserver.port=9002 -Djforex.system.back-test.start=2016-10-19 target/trading-0.5.0-SNAPSHOT.jar &
#java -jar -Dserver.port=9003 -Djforex.system.back-test.start=2016-10-16 target/trading-0.5.0-SNAPSHOT.jar &
#java -jar -Dserver.port=9004 -Djforex.system.back-test.start=2016-10-23 target/trading-0.5.0-SNAPSHOT.jar &