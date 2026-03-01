#!/bin/zsh
# =============================================================================
# MONITOR — Watch Spring Boot app during load testing
# =============================================================================
# Usage: ./scripts/monitor.sh
#
# Shows CPU, Memory, Threads every 2 seconds.
# Run this in a separate terminal while k6 is running.
# Press Ctrl+C to stop.
# =============================================================================

# Find the Spring Boot app PID
PID=$(jps -l 2>/dev/null | grep "io.scalelab.ScalelabApplication" | awk '{print $1}')

if [ -z "$PID" ]; then
    echo "❌ ScalelabApplication is not running!"
    echo "   Start it from IntelliJ or run: ./mvnw spring-boot:run"
    exit 1
fi

echo "✅ Found ScalelabApplication — PID: $PID"
echo "   Monitoring every 2 seconds... (Ctrl+C to stop)"
echo ""
echo "TIME                  CPU%    MEM(MB)   THREADS"
echo "----                  ----    -------   -------"

while true; do
    # Get CPU and memory from ps
    STATS=$(ps -p $PID -o %cpu=,rss= 2>/dev/null)

    if [ -z "$STATS" ]; then
        echo "❌ Process $PID has stopped!"
        exit 1
    fi

    CPU=$(echo $STATS | awk '{print $1}')
    MEM_KB=$(echo $STATS | awk '{print $2}')
    MEM_MB=$((MEM_KB / 1024))

    # Get thread count
    THREADS=$(ps -M -p $PID 2>/dev/null | wc -l | tr -d ' ')

    TIMESTAMP=$(date '+%H:%M:%S')

    printf "%-22s%-8s%-10s%-8s\n" "$TIMESTAMP" "$CPU" "$MEM_MB" "$THREADS"

    sleep 2
done

