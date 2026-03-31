from machine import Pin, PWM, UART
import time
import utime
import sys

# --- GLOBAL STATE VARIABLE ---
# 0 = OBSTACLE_AVOID (default), 1 = RC_MODE, 2 = CHASE_MODE, 4 = AUTONOMOUS
current_mode = 0  

# --- RC activity tracking ---
RC_TIMEOUT_MS = 1500000000000000000000000  # effectively no timeout
last_rc_cmd_time = 0         # last time we saw an RC move command
rc_active = False            # True while motors are being driven by RC

# --- BLUETOOTH SETUP (UART0 on GP12/GP13) ---
BLUETOOTH_BAUD_RATE = 9600
bt_uart = UART(0, baudrate=BLUETOOTH_BAUD_RATE, tx=Pin(12), rx=Pin(13))
print("SYSTEM: UART configured on GP12/GP13. Waiting for connection.")

# --- SENSOR PINS ---
trigger = Pin(0, Pin.OUT)
echo = Pin(1, Pin.IN)

right_sensor = Pin(8, Pin.IN)
left_sensor = Pin(9, Pin.IN)

# --- MOTOR DRIVER PINS ---
ENA = PWM(Pin(7))  # left motor enable
IN1 = Pin(6, Pin.OUT)
IN2 = Pin(5, Pin.OUT)

IN3 = Pin(4, Pin.OUT)
IN4 = Pin(3, Pin.OUT)
ENB = PWM(Pin(2))  # right motor enable

ENA.freq(1000)
ENB.freq(1000)

CHASE_SPEED = 50000
RC_SPEED = 30000
current_speed = CHASE_SPEED

# --- MOVEMENT FUNCTIONS ---
def set_speed(s):
    global current_speed
    current_speed = s
    ENA.duty_u16(current_speed)
    ENB.duty_u16(current_speed)

def forward():
    ENA.duty_u16(current_speed)
    IN1.high(); IN2.low()      # left forward
    ENB.duty_u16(current_speed)
    IN3.low(); IN4.high()      # right forward

def backward():
    ENA.duty_u16(current_speed)
    IN1.low(); IN2.high()      # left backward
    ENB.duty_u16(current_speed)
    IN3.high(); IN4.low()      # right backward

def turn_left():
    ENA.duty_u16(current_speed)
    IN1.high(); IN2.low()      # left forward
    ENB.duty_u16(current_speed)
    IN3.high(); IN4.low()      # right backward (pivot left)

def turn_right():
    ENA.duty_u16(current_speed)
    IN1.low(); IN2.high()      # left backward
    ENB.duty_u16(current_speed)
    IN3.low(); IN4.high()      # right forward (pivot right)

def stop():
    ENA.duty_u16(0)
    ENB.duty_u16(0)
    IN1.low(); IN2.low()
    IN3.low(); IN4.low()

# --------- NEW DIAGONAL / CURVED MOVES ----------

def forward_left():
    """Move forward but curve to the left (right wheel faster)."""
    left_speed = int(current_speed * 0.6)   # slow left wheel
    ENA.duty_u16(left_speed)
    IN1.high(); IN2.low()

    ENB.duty_u16(current_speed)
    IN3.low(); IN4.high()

def forward_right():
    """Move forward but curve to the right (left wheel faster)."""
    right_speed = int(current_speed * 0.6)  # slow right wheel
    ENA.duty_u16(current_speed)
    IN1.high(); IN2.low()

    ENB.duty_u16(right_speed)
    IN3.low(); IN4.high()

def backward_left():
    """Move backward but curve to the left (right wheel faster backward)."""
    left_speed = int(current_speed * 0.6)
    ENA.duty_u16(left_speed)
    IN1.low(); IN2.high()

    ENB.duty_u16(current_speed)
    IN3.high(); IN4.low()

def backward_right():
    """Move backward but curve to the right (left wheel faster backward)."""
    right_speed = int(current_speed * 0.6)
    ENA.duty_u16(current_speed)
    IN1.low(); IN2.high()

    ENB.duty_u16(right_speed)
    IN3.high(); IN4.low()

# --- ULTRASONIC ---
def get_distance():
    trigger.low()
    utime.sleep_us(2)
    trigger.high()
    utime.sleep_us(10)
    trigger.low()

    timeout_us = 30000
    start = utime.ticks_us()

    # wait for echo to go high
    while echo.value() == 0:
        if utime.ticks_diff(utime.ticks_us(), start) > timeout_us:
            return None
        utime.sleep_us(5)

    signal_on = utime.ticks_us()

    # wait for echo to go low
    while echo.value() == 1:
        if utime.ticks_diff(utime.ticks_us(), signal_on) > timeout_us:
            return None
        utime.sleep_us(5)

    signal_off = utime.ticks_us()
    pulse = utime.ticks_diff(signal_off, signal_on)
    return pulse * 0.01715   # cm

# --- BLUETOOTH COMMAND PROCESSING ---
def check_bluetooth_commands():
    global current_mode, last_rc_cmd_time, rc_active

    if not bt_uart.any():
        return

    data = bt_uart.read()      # read all available bytes
    if not data:
        return

    try:
        text = data.decode().strip()
    except UnicodeError:
        return

    if not text:
        return

    # Use the LAST non-whitespace character as the command
    command = text[-1]
    print("BT:", repr(command))

    # ----- MODE COMMANDS -----
    if command == "2":  # Autonomous (your naming)
        current_mode = 2
        set_speed(CHASE_SPEED)
        stop()
        rc_active = False
        print("MODE: Autonomous")
        return
    
    if command == "4":  # Chase mode
        current_mode = 4
        set_speed(CHASE_SPEED)
        stop()
        rc_active = False
        print("MODE: CHASE")
        return

    if command == "1":  # RC mode
        current_mode = 1
        set_speed(RC_SPEED)
        stop()
        rc_active = False
        print("MODE: RC")
        return

    if command == "3":  # Obstacle Avoidance mode
        current_mode = 0
        set_speed(CHASE_SPEED)
        stop()
        rc_active = False
        print("MODE: OBSTACLE AVOID")
        return

    if command == "S":  # STOP motors ONLY (stay in current mode)
        stop()
        print("STOP")
        return

    # ----- RC MOVEMENT (only in RC mode) -----
    if current_mode == 1:
        # Straight moves
        if command == "F":
            forward()
            rc_active = True
            last_rc_cmd_time = utime.ticks_ms()
            print("RC: Forward")

        elif command == "B":
            backward()
            rc_active = True
            last_rc_cmd_time = utime.ticks_ms()
            print("RC: Backward")

        elif command == "L":
            turn_left()
            rc_active = True
            last_rc_cmd_time = utime.ticks_ms()
            print("RC: Left")

        elif command == "R":
            turn_right()
            rc_active = True
            last_rc_cmd_time = utime.ticks_ms()
            print("RC: Right")

        # ----- NEW DIAGONAL COMMANDS -----
        elif command == "G":   # forward-left
            forward_left()
            rc_active = True
            last_rc_cmd_time = utime.ticks_ms()
            print("RC: Forward-Left")

        elif command == "I":   # forward-right
            forward_right()
            rc_active = True
            last_rc_cmd_time = utime.ticks_ms()
            print("RC: Forward-Right")

        elif command == "H":   # backward-left
            backward_left()
            rc_active = True
            last_rc_cmd_time = utime.ticks_ms()
            print("RC: Backward-Left")

        elif command == "J":   # backward-right
            backward_right()
            rc_active = True
            last_rc_cmd_time = utime.ticks_ms()
            print("RC: Backward-Right")

# --- OBSTACLE AVOIDANCE MODE (default, mode 0) ---
def run_obstacle_avoid_mode():
    dis = get_distance()
    if dis is None:
        stop()
        print("Avoidance: Ultrasonic timeout")
        return

    if dis < 10:
        stop()
        backward()
        utime.sleep_ms(300)
        stop()
        print("Avoidance")
        return

    right = right_sensor.value()
    left = left_sensor.value()

    if right == 0 and left == 0:
        backward()
        return
    if right == 0 and left == 1:
        turn_left()
        utime.sleep_ms(50)
        return
    if right == 1 and left == 0:
        turn_right()
        utime.sleep_ms(50)
        return

    stop()
    print("Avoidance: Lost target")

# --- Autonomous MODE (mode 4) ---
def autonomous_mode():
    dis = get_distance()
    if dis is None:
        stop()
        print("Ultrasonic timeout")
        return

    if dis < 10:
        stop()
        backward()
        utime.sleep_ms(300)
        stop()
        print("Avoidance")
        return

    right = right_sensor.value()
    left = left_sensor.value()

    if right == 0 and left == 0:
        backward()
        return
    if right == 0 and left == 1:
        turn_left()
        utime.sleep_ms(50)
        return
    if right == 1 and left == 0:
        turn_right()
        utime.sleep_ms(50)
        return

    forward()
    print("reaching target")

# --- CHASE MODE (mode 2 in your naming earlier, now 2/4 split, but keeping function) ---
def run_chase_mode():
    dis = get_distance()
    if dis is None:
        stop()
        print("CHASE: Ultrasonic timeout")
        return

    if dis < 10:
        stop()
        forward()
        utime.sleep_ms(300)
        stop()
        print("CHASE")
        return

    right = right_sensor.value()
    left = left_sensor.value()

    if right == 0 and left == 0:
        forward()
        return
    if right == 0 and left == 1:
        turn_right()
        utime.sleep_ms(50)
        return
    if right == 1 and left == 0:
        turn_left()
        utime.sleep_ms(50)
        return

    stop()
    print("CHASE: Lost target")

# --- MAIN LOOP ---
print("System Started. Default mode = OBSTACLE AVOIDANCE")

while True:
    check_bluetooth_commands()

    if current_mode == 2:
        # Autonomous (as you labeled in BT prints)
        run_chase_mode()
        utime.sleep_ms(10)

    elif current_mode == 1:
        # RC mode: with your huge RC_TIMEOUT_MS, this effectively never auto-stops
        if rc_active:
            now = utime.ticks_ms()
            if utime.ticks_diff(now, last_rc_cmd_time) > RC_TIMEOUT_MS:
                stop()
                rc_active = False
        utime.sleep_ms(20)

    elif current_mode == 0:
        # DEFAULT: obstacle avoidance (US + IR)
        run_obstacle_avoid_mode()
        utime.sleep_ms(50)
        
    elif current_mode == 4:
        # Autonomous mode (US + IR)
        autonomous_mode()
        utime.sleep_ms(50)
        
    elif current_mode == -1:
        utime.sleep_ms(50)  # do nothing


#from machine import Pin
#import time

#pin = Pin(15, Pin.IN)   # input pin where the signal is connected

#def measure_frequency(pin, sample_time=1):
#count = 0
#start = time.ticks_ms()
#while time.ticks_diff(time.ticks_ms(), start) < sample_time * 1000:
    #while pin.value() == 0:
        #pass
    #while pin.value() == 1:
        #pass
    #count += 1
#return count / sample_time

#while True:
#freq = measure_frequency(pin, 1)   # sample time = 1 second
#print("Frequency =", freq, "Hz")
