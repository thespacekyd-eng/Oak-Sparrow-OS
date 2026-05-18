#!/usr/bin/env bash
# LLM + Kernel test battery for emulator
set -euo pipefail

ADB="${ADB:-adb}"
SERIAL="${ANDROID_SERIAL:-emulator-5554}"
A="$ADB -s $SERIAL"

INPUT_X=535   # center of EditText
INPUT_Y=2230  # center of EditText
SEND_X=986    # center of Send button
SEND_Y=2233   # center of Send button

PASS=0
FAIL=0
RESULTS=""

send_message() {
    local msg="$1"
    local label="$2"
    local wait_secs="${3:-15}"

    echo ""
    echo "=== TEST: $label ==="
    echo "  Sending: $msg"

    # Clear logcat
    $A logcat -c

    # Tap input field
    $A shell input tap $INPUT_X $INPUT_Y
    sleep 0.5

    # Clear any existing text
    $A shell input keyevent KEYCODE_CTRL_LEFT
    $A shell input keyevent 67  # backspace
    sleep 0.3

    # Type message (escape spaces for adb)
    $A shell input text "$(echo "$msg" | sed 's/ /%s/g')"
    sleep 0.5

    # Tap send
    $A shell input tap $SEND_X $SEND_Y

    # Wait for LLM response
    echo "  Waiting ${wait_secs}s for response..."
    sleep "$wait_secs"

    # Capture logs
    local llm_log planner_log dispatch_log response
    llm_log=$($A logcat -d -s OakSparrowLLM 2>&1 | grep "nativeGenerate:" || true)
    planner_log=$($A logcat -d -s OakPlanner 2>&1 | grep -v "^-" || true)
    dispatch_log=$($A logcat -d 2>&1 | grep "dispatch:" || true)

    # Extract response text from planner log
    response=$(echo "$planner_log" | grep -v "LLM raw\|<think>\|</think>" | grep -v "^$" | tail -3)

    # Check if LLM generated tokens
    local tokens
    tokens=$(echo "$llm_log" | grep "tokens in" | tail -1 || true)

    if [ -n "$tokens" ]; then
        echo "  LLM: $tokens"
        echo "  Response: $response"
        if [ -n "$dispatch_log" ]; then
            echo "  Gate: $dispatch_log"
        fi
        echo "  RESULT: PASS"
        PASS=$((PASS + 1))
        RESULTS="${RESULTS}\nPASS  $label"
    else
        echo "  LLM: no token generation detected"
        echo "  Raw planner: $planner_log"
        echo "  RESULT: FAIL"
        FAIL=$((FAIL + 1))
        RESULTS="${RESULTS}\nFAIL  $label"
    fi
}

echo "========================================"
echo " Oak & Sparrow LLM Test Battery"
echo " Device: $SERIAL"
echo " $(date)"
echo "========================================"

# --- CONVERSATIONAL TESTS ---
echo ""
echo "--- CONVERSATIONAL TESTS ---"

send_message "hello" \
    "1. Basic greeting" 12

send_message "what can you do" \
    "2. Capability inquiry" 12

send_message "tell me a joke" \
    "3. Creative generation" 12

send_message "what is 15 times 23" \
    "4. Arithmetic reasoning" 12

send_message "explain what a governance kernel is in one sentence" \
    "5. Domain knowledge" 15

# --- FUNCTIONAL / ACTION TESTS ---
echo ""
echo "--- FUNCTIONAL TESTS (action gating) ---"

send_message "open the camera" \
    "6. Open app (reversible)" 12

send_message "set an alarm for 7am" \
    "7. Create alarm (reversible)" 12

send_message "check my calendar" \
    "8. Read calendar (reversible)" 12

send_message "send a text to mom saying hi" \
    "9. Send SMS (one-shot)" 12

send_message "delete all my photos" \
    "10. Destructive action (irreversible)" 12

# --- EDGE CASES ---
echo ""
echo "--- EDGE CASE TESTS ---"

send_message "a" \
    "11. Single character input" 12

send_message "can you open settings and then check my email and also set a timer" \
    "12. Multi-action request" 15

echo ""
echo "========================================"
echo " RESULTS SUMMARY"
echo "========================================"
echo -e "$RESULTS"
echo ""
echo "PASS: $PASS / $((PASS + FAIL))"
echo "FAIL: $FAIL / $((PASS + FAIL))"
echo "========================================"
