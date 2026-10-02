#!/usr/bin/env python3
import base64
import socket
import time
import threading
import json
import dotenv
import os
from datetime import datetime
from collections import Counter




def parse_rtcm3_message(data: bytes, offset: int = 0) -> tuple:
    """
    Parse RTCM3 message at given offset.
    Returns (message_type, length, end_offset) or (None, None, offset+1) if no valid message.
    
    RTCM3 frame structure:
    - Byte 0: 0xD3 (sync)
    - Bytes 1-2: 6 reserved bits + 10-bit length field
    - Bytes 3+: payload (first 12 bits = message type)
    """
    if offset + 3 > len(data):
        return (None, None, offset + 1)
    
    if data[offset] != 0xD3:
        return (None, None, offset + 1)
    
    # Extract 10-bit length from bytes 1-2
    # Byte 1: bits 7-2 contain upper 6 bits of length (bits 1-2 are reserved)
    # Byte 2: all 8 bits contain lower 8 bits of length
    length = ((data[offset + 1] & 0x3F) << 8) | data[offset + 2]
    
    frame_size = 3 + length +3  # 3 header bytes + payload + 3 CRC bytes
    
    if offset + frame_size > len(data):
        return (None, None, offset + 1)
    
    # Extract message type (first 12 bits of payload, i.e., bytes 3-4)
    if offset + 5 > len(data):
        return (None, None, offset + 1)
    
    msg_type = ((data[offset + 3] << 4) | ((data[offset + 4] >> 4) & 0x0F)) & 0xFFF
    
    return (msg_type, length, offset + frame_size)


def extract_all_rtcm_messages(data: bytes) -> list:
    """Extract all RTCM3 messages from data, returns list of message types."""
    messages = []
    offset = 0
    
    while offset < len(data):
        msg_type, length, next_offset = parse_rtcm3_message(data, offset)
        
        if msg_type is not None:
            messages.append(msg_type)
            offset = next_offset
        else:
            offset = next_offset
    
    return messages


def extract_all_rtcm_frames(data: bytes) -> list:
    """Extract all RTCM3 frames from data, returns list of (msg_type, frame_bytes, length) tuples."""
    frames = []
    offset = 0
    
    while offset < len(data):
        msg_type, length, next_offset = parse_rtcm3_message(data, offset)
        
        if msg_type is not None:
            frame_bytes = data[offset:next_offset]
            frames.append((msg_type, frame_bytes, length))
            offset = next_offset
        else:
            offset = next_offset
    
    return frames


def nmea_checksum(sentence_body: str) -> str:
    csum = 0
    for ch in sentence_body:
        csum ^= ord(ch)
    return f"{csum:02X}"


def make_gga(lat: float, lon: float, alt_m: float) -> str:
    lat_hemi = "N" if lat >= 0 else "S"
    lon_hemi = "E" if lon >= 0 else "W"

    lat = abs(lat)
    lon = abs(lon)

    lat_deg = int(lat)
    lon_deg = int(lon)
    lat_min = (lat - lat_deg) * 60.0
    lon_min = (lon - lon_deg) * 60.0

    lat_field = f"{lat_deg:02d}{lat_min:07.4f}"
    lon_field = f"{lon_deg:03d}{lon_min:07.4f}"

    # Use current UTC time in HHMMSS format
    utc_time = time.strftime("%H%M%S", time.gmtime())

    body = (
        f"GPGGA,{utc_time},{lat_field},{lat_hemi},"
        f"{lon_field},{lon_hemi},"
        f"4,28,1.0,{alt_m:.1f},M,0.0,M,,"
    )
    return f"${body}*{nmea_checksum(body)}\r\n"


class NTRIPPreTest:
    def __init__(self):
        self.sock = None
        self.connected = False
        self.gga_sent = False
        self.rtcm_received = False
        self._stop = threading.Event()
        self.buffer = b""

    def connect(self) -> bool:
        try:
            self.sock = socket.create_connection(
                (CASTER_HOST, CASTER_PORT),
                timeout=SOCKET_TIMEOUT_SEC,
            )
            self.sock.settimeout(SOCKET_TIMEOUT_SEC)

            auth = base64.b64encode(f"{USERNAME}:{PASSWORD}".encode("ascii")).decode("ascii")
            request = (
                f"GET /{MOUNTPOINT} HTTP/1.0\r\n"
                f"User-Agent: NTRIP-PreTest/1.0\r\n"
                f"Authorization: Basic {auth}\r\n"
                f"\r\n"
            )

            self.sock.sendall(request.encode("ascii"))

            resp = b""
            while b"\r\n\r\n" not in resp:
                chunk = self.sock.recv(1024)
                if not chunk:
                    raise RuntimeError("caster closed connection")
                resp += chunk
                if len(resp) > 8192:
                    break

            # Split headers and any remaining data
            header_end = resp.find(b"\r\n\r\n")
            headers = resp[:header_end].decode("ascii", errors="ignore")
            first_line = headers.split("\r\n", 1)[0]

            if "200" in first_line:
                self.connected = True
                print("✅ RTK caster reachable")
                print(f"   Response: {first_line}")
                # Store any data received after headers for processing
                self.buffer = resp[header_end + 4:]
                return True

            print("❌ Caster reachable, but mountpoint/auth failed")
            print(f"   Response: {first_line}")
            return False

        except Exception as e:
            print(f"❌ RTK caster not reachable: {e}")
            return False

    def send_gga_once(self):
        if not self.connected or not self.sock:
            return

        gga = make_gga(FAKE_LAT, FAKE_LON, FAKE_ALT_M)
        try:
            self.sock.sendall(gga.encode("ascii"))
            self.gga_sent = True
            print("✅ Fake GGA sent to caster")
            print(f"   {gga.strip()}")
        except Exception as e:
            print(f"❌ Failed to send GGA: {e}")

    def gga_heartbeat(self):
        while not self._stop.is_set() and self.connected:
            try:
                gga = make_gga(FAKE_LAT, FAKE_LON, FAKE_ALT_M)
                self.sock.sendall(gga.encode("ascii"))
                time.sleep(GGA_INTERVAL_SEC)
            except Exception:
                break

    def stream_rtcm_messages(self, num_messages: int = 20) -> list:
        """Stream and collect RTCM messages, returns list of message types received."""
        if not self.connected or not self.sock:
            return []

        collected_messages = []
        data_buffer = self.buffer  # Start with any buffered data
        end_time = time.time() + RTCM_WAIT_SEC
        
        try:
            print(f"\n📡 Streaming up to {num_messages} RTCM messages...")
            
            while len(collected_messages) < num_messages and time.time() < end_time and not self._stop.is_set():
                try:
                    data = self.sock.recv(4096)
                    if not data:
                        time.sleep(0.1)
                        continue
                    
                    data_buffer += data
                    
                    # Extract all complete RTCM messages from buffer
                    messages = extract_all_rtcm_messages(data_buffer)
                    
                    if messages:
                        # Find the last complete message and rebuild buffer
                        offset = 0
                        for i, msg in enumerate(messages):
                            if i < len(messages) - 1 or b"\xD3" in data_buffer:
                                # Determine offset of last complete message
                                offset = 0
                                for _ in range(i + 1):
                                    _, _, offset = parse_rtcm3_message(data_buffer, offset)
                        
                        # Keep only incomplete data in buffer
                        data_buffer = data_buffer[offset:]
                        
                        # Collect messages up to num_messages
                        for msg_type in messages:
                            if len(collected_messages) < num_messages:
                                collected_messages.append(msg_type)
                                print(f"   Message {len(collected_messages)}: Type {msg_type:4d}")
                
                except socket.timeout:
                    time.sleep(0.1)
                    continue

            if collected_messages:
                self.rtcm_received = True
                print(f"\n✅ Collected {len(collected_messages)} RTCM messages")
            else:
                print("❌ No RTCM messages collected")
            
            return collected_messages

        except Exception as e:
            print(f"❌ Error streaming RTCM: {e}")
            return collected_messages

    def buffer_latest_messages(self, duration_sec: int = 60, target_types: list = None) -> dict:
        """
        Buffer latest messages for each target type for specified duration.
        Every second, collects latest (type, hexstream, length) for each target type.
        Returns dict with ISO timestamps as keys and list of (type, hexstream, length) as values.
        """
        if target_types is None:
            target_types = [1006, 1074, 1094, 1124]
        
        if not self.connected or not self.sock:
            return {}

        latest_frames = {}  # Dict to hold latest frame for each type: {msg_type: (type, hex_str, length)}
        timestamped_data = {}
        data_buffer = self.buffer
        end_time = time.time() + duration_sec
        last_collection_time = time.time()
        total_frames_processed = 0
        
        try:
            print(f"\n📡 Buffering latest RTCM messages for {duration_sec} seconds...")
            print(f"   Target types: {target_types}")
            
            while time.time() < end_time and not self._stop.is_set():
                try:
                    data = self.sock.recv(4096)
                    if not data:
                        time.sleep(0.01)
                        continue
                    
                    data_buffer += data
                    
                    # Extract all complete frames from buffer
                    frames = extract_all_rtcm_frames(data_buffer)
                    
                    if frames:
                        # Update latest frame for each message type
                        for msg_type, frame_bytes, length in frames:
                            hex_stream = frame_bytes.hex().upper()
                            latest_frames[msg_type] = (msg_type, hex_stream, length)
                            total_frames_processed += 1
                        
                        # Find the last complete frame and rebuild buffer
                        offset = 0
                        for _ in range(len(frames)):
                            _, _, offset = parse_rtcm3_message(data_buffer, offset)
                        
                        # Keep only incomplete data in buffer
                        data_buffer = data_buffer[offset:]
                    
                except socket.timeout:
                    time.sleep(0.01)
                    continue
                
                # Every second, collect snapshot of latest messages
                current_time = time.time()
                if current_time - last_collection_time >= 1.0:
                    timestamp = datetime.now().isoformat()
                    
                    # Collect only target types that we have latest frames for
                    messages_at_time = [
                        latest_frames[msg_type]
                        for msg_type in target_types
                        if msg_type in latest_frames
                    ]
                    
                    if messages_at_time:
                        timestamped_data[timestamp] = messages_at_time
                        type_list = [m[0] for m in messages_at_time]
                        print(f"   [{timestamp}] Snapshot: {len(messages_at_time)} types {type_list}")
                    
                    last_collection_time = current_time

            print(f"✅ Buffering complete. Processed {total_frames_processed} frames total")
            print(f"✅ Collected {len(timestamped_data)} timestamps")
            return timestamped_data

        except Exception as e:
            print(f"❌ Error buffering latest messages: {e}")
            import traceback
            traceback.print_exc()
            return timestamped_data

    def close(self):
        self._stop.set()
        try:
            if self.sock:
                self.sock.close()
        except Exception:
            pass


def main():
    test = NTRIPPreTest()
    try:
        if not test.connect():
            return

        # Send one fake GGA immediately
        test.send_gga_once()

        # Keep sending GGA in the background
        t = threading.Thread(target=test.gga_heartbeat, daemon=True)
        t.start()

        # Buffer latest messages for 60 seconds
        target_types = [1006, 1074, 1094, 1124]
        timestamped_data = test.buffer_latest_messages(duration_sec=60, target_types=target_types)

        print("\n--- RESULT ---")
        print(f"caster reachable: {test.connected}")
        print(f"GGA sent:         {test.gga_sent}")
        print(f"Data points collected: {len(timestamped_data)}")
        
        # Save to JSON
        if timestamped_data:
            json_output = {}
            for timestamp, messages in timestamped_data.items():
                json_output[timestamp] = [
                    {
                        "type": msg[0],
                        "hexstream": msg[1],
                        "length": msg[2]
                    }
                    for msg in messages
                ]

                for msg in messages:
                    print(msg)
            
            output_file = "rtcm_messages.json"
            with open(output_file, "w") as f:
                json.dump(json_output, f, indent=2)
            print(f"✅ Saved to {output_file}")
            
            # Print summary
            print("\nMessage Type Summary:")
            type_counts = Counter()
            for messages in timestamped_data.values():
                for msg_type, _, _ in messages:
                    type_counts[msg_type] += 1
            
            for msg_type in sorted(type_counts.keys()):
                print(f"   Type {msg_type:4d}: {type_counts[msg_type]:3d} occurrences")
        else:
            print("❌ No data collected")

    finally:
        test.close()


if __name__ == "__main__":
    dotenv.load_dotenv()
    CASTER_HOST = os.getenv("CASTER_HOST")
    CASTER_PORT = int(os.getenv("CASTER_PORT"))
    MOUNTPOINT = os.getenv("CASTER_MOUNTPOINT")
    USERNAME = os.getenv("CASTER_USERNAME")
    PASSWORD = os.getenv("CASTER_PASSWORD")

    # Fake rover position for pre-test GGA
    FAKE_LAT = float(os.getenv("FAKE_LAT"))
    FAKE_LON = float(os.getenv("FAKE_LON"))
    FAKE_ALT_M = float(os.getenv("FAKE_ALT_M"))

    GGA_INTERVAL_SEC = int(os.getenv("GGA_INTERVAL_SEC"))
    RTCM_WAIT_SEC = int(os.getenv("RTCM_WAIT_SEC"))
    SOCKET_TIMEOUT_SEC = int(os.getenv("SOCKET_TIMEOUT_SEC"))

    main()
