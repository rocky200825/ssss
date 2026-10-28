"""
Phone Mic Bridge - Windows receiver.

Listens on TCP port 5555. The Android app connects, sends "PAIR <code>\n",
and then streams raw 16-bit mono 16 kHz PCM. That audio is played into
VB-CABLE ("CABLE Input"); apps then use "CABLE Output" as their microphone.
"""
import json
import os
import secrets
import socket
import sys
import threading
from pathlib import Path

import sounddevice as sd

PORT = 5555
RATE = 16000
BLOCK = 640                 # 40 ms of audio per callback
MAX_BUFFER_SEC = 0.3        # cap latency; drop oldest audio beyond this
DEFAULT_DEVICE = "CABLE Input"

BASE = Path(sys.executable).parent if getattr(sys, "frozen", False) else Path(__file__).parent
CONFIG_PATH = BASE / "config.json"


def load_config():
    cfg = {}
    if CONFIG_PATH.exists():
        cfg = json.loads(CONFIG_PATH.read_text())
    if "code" not in cfg:
        cfg["code"] = f"{secrets.randbelow(900000) + 100000}"  # 6-digit pairing code
    cfg.setdefault("device", DEFAULT_DEVICE)
    CONFIG_PATH.write_text(json.dumps(cfg, indent=2))
    return cfg


def find_output_device(name):
    for index, dev in enumerate(sd.query_devices()):
        if dev["max_output_channels"] > 0 and name.lower() in dev["name"].lower():
            return index
    return None


class Playback:
    """Plays incoming PCM through a callback; keeps a small buffer to limit lag."""

    def __init__(self, device_index):
        self.lock = threading.Lock()
        self.buf = bytearray()
        self.max_bytes = int(RATE * 2 * MAX_BUFFER_SEC)
        self.stream = sd.RawOutputStream(
            samplerate=RATE, channels=1, dtype="int16",
            device=device_index, blocksize=BLOCK, callback=self._callback,
        )

    def _callback(self, outdata, frames, time, status):
        n = frames * 2
        with self.lock:
            chunk = bytes(self.buf[:n])
            del self.buf[:n]
        outdata[:len(chunk)] = chunk
        if len(chunk) < n:
            outdata[len(chunk):n] = b"\x00" * (n - len(chunk))

    def push(self, data):
        with self.lock:
            self.buf += data
            extra = len(self.buf) - self.max_bytes
            if extra > 0:
                del self.buf[:extra]

    def reset(self):
        with self.lock:
            self.buf.clear()


def local_ips():
    ips = set()
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("10.255.255.255", 1))
            ips.add(s.getsockname()[0])
    except OSError:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ips.add(info[4][0])
    except OSError:
        pass
    return sorted(ip for ip in ips if not ip.startswith(("127.", "169.254.")))


def handle_client(conn, addr, cfg, playback):
    try:
        line = conn.makefile("rb").readline().decode(errors="ignore").strip()
        if not line.startswith("PAIR ") or not secrets.compare_digest(line[5:], cfg["code"]):
            conn.sendall(b"NO\n")
            print(f"Rejected connection from {addr[0]} (wrong code)")
            return
        conn.sendall(b"OK\n")
        print(f"Phone connected from {addr[0]}")
        playback.reset()
        while True:
            data = conn.recv(4096)
            if not data:
                break
            playback.push(data)
    except OSError:
        pass
    finally:
        conn.close()
        print("Phone disconnected - waiting for it to reconnect...")


def main():
    cfg = load_config()
    device_index = find_output_device(cfg["device"])
    if device_index is None:
        print(f"Could not find an output device containing '{cfg['device']}'.")
        print("Install VB-CABLE (see README) or edit config.json to match your device.")
        print("Available output devices:")
        for i, d in enumerate(sd.query_devices()):
            if d["max_output_channels"] > 0:
                print(f"  {i}: {d['name']}")
        input("Press Enter to exit...")
        return

    playback = Playback(device_index)
    playback.stream.start()

    print("=" * 50)
    print(" Phone Mic Bridge is running")
    print(f" Playing into: {sd.query_devices(device_index)['name']}")
    print(f" Pairing code: {cfg['code']}")
    for ip in local_ips():
        print(f" Phone address: {ip}:{PORT}")
    print("=" * 50)
    print("Enter the address and code in the phone app. Ctrl+C to quit.")

    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(("0.0.0.0", PORT))
    server.listen(1)
    try:
        while True:
            conn, addr = server.accept()
            threading.Thread(target=handle_client, args=(conn, addr, cfg, playback), daemon=True).start()
    except KeyboardInterrupt:
        pass
    finally:
        playback.stream.stop()
        playback.stream.close()
        server.close()


if __name__ == "__main__":
    main()
