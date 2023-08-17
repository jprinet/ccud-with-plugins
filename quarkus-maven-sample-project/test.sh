#!/usr/bin/env bash

function assertNoConfigDump() {
    local buildLog=$1
    local quarkusPropertiesNotFound=$(grep ".quarkus/quarkus-prod-config-dump not found" ${buildLog} | wc -l)
    local cachingNotPossible=$(grep "Caching not possible for Quarkus goal" ${buildLog} | wc -l)

    if [ "${quarkusPropertiesNotFound}" == "0" ] || [ "${cachingNotPossible}" == "0" ]
    then
        echo "ERROR - config dump is present"
        exit 1
    fi
}

function assertCacheHitCount() {
    local buildLog=$1
    local count=$2
    local cacheHit=$(grep "Configuring caching for Quarkus build" -A 1 ${buildLog} | grep "Loaded from the build cache" | wc -l)

    if [ "${cacheHit}" != "${count}" ]
    then
        echo "ERROR - cache hit = ${cacheHit}"
        exit 1
    fi
}

function assertCacheMiss() {
    assertCacheHitCount $1 0
}

function assertCacheHit() {
    assertCacheHitCount $1 1
}

function assertPropertyChanged() {
    local buildLog=$1
    local quarkusProp=$2
    local quarkusPropChanged=$(grep -E "Quarkus properties have changed.*${quarkusProp}" ${buildLog} | wc -l)

    if [ "$quarkusPropChanged" != "1" ]
    then
        echo "ERROR - property change not detected"
        exit 1
    fi
}

# Clean local cache
rm -rf ~/.m2/.gradle-enterprise/build-cache

# Clean quarkus dump
rm -rf .quarkus/*-config-dump

# Clean log files
rm -f /tmp/build*.log

# Run build 1
echo "Run build 1 - Expect no config dump"
buildLog="/tmp/build1.log"
./mvnw -B clean package -DskipTests 2>&1 | tee -a ${buildLog}
assertNoConfigDump ${buildLog}

# Run build 2
echo "Run build 2 - Expect cache miss"
buildLog="/tmp/build2.log"
./mvnw -B clean package -DskipTests 2>&1 | tee -a ${buildLog}
assertCacheMiss ${buildLog}

# Run build 3
echo "Run build 3 - Expect cache hit"
buildLog="/tmp/build3.log"
./mvnw -B clean package -DskipTests 2>&1 | tee -a ${buildLog}
assertCacheHit ${buildLog}

# Run build 4
echo "Run build 4 - Expect property changed"
buildLog="/tmp/build4.log"
quarkusProperty="quarkus.live-reload.retry-interval"
./mvnw -B clean package -DskipTests -D${quarkusProperty}=15s 2>&1 | tee -a ${buildLog}
assertPropertyChanged ${buildLog} ${quarkusProperty}

# Run build 5
echo "Run build 5 - Expect cache hit"
buildLog="/tmp/build5.log"
./mvnw -B clean package -DskipTests -D${quarkusProperty}=15s 2>&1 | tee -a ${buildLog}
assertCacheHit ${buildLog}

# Run build 6
echo "Run build 6 - Expect cache miss"
buildLog="/tmp/build6.log"
./mvnw -B clean package -DskipTests -Dnative -D${quarkusProperty}=15s 2>&1 | tee -a ${buildLog}
assertCacheMiss ${buildLog}

# Run build 7
echo "Run build 7 - Expect cache hit"
buildLog="/tmp/build7.log"
./mvnw -B clean package -DskipTests -Dnative -D${quarkusProperty}=15s 2>&1 | tee -a ${buildLog}
assertCacheHit ${buildLog}

# Run build 8
rm -rf .quarkus/*-config-dump
echo "Run build 8 - Expect no config dump"
buildLog="/tmp/build8.log"
./mvnw -B clean package -DskipTests -Dnative 2>&1 | tee -a ${buildLog}
assertNoConfigDump ${buildLog}

echo "TEST SUCCESSFUL"
