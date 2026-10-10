"""LOOM live dashboard — shows the virtual chiplets and the Weaver working, in real time.

The page is a React app (source: tools/dashboard/src, built bundle: tools/dashboard/dist).
The bundle is committed, so the laptop needs only Python — no npm, no internet.

The phone app has no network permission. It writes one JSON line per second to logcat
(tag LOOM_LIVE) plus one line per event (tag LOOM_EVT). This script reads them over the USB
debug link with `adb logcat`, and serves a page at http://localhost:8765 that updates live.
Demo buttons on the page send commands back with `adb shell am broadcast`.

Usage (Windows: double-click live_dashboard.bat):
    python tools/live_dashboard.py            # phone on USB, or on the same Wi-Fi after wifi_connect.bat
    python tools/live_dashboard.py --demo     # replay of a simulated-phone session (rehearsal only)

Python standard library only. Every session is also saved to live_logs/session_<time>.jsonl.
"""
import argparse
import json
import os
import queue
import re
import shutil
import socket
import subprocess
import sys
import threading
import time
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
PACKAGE = "com.loom.runtime"
ACTION = "com.loom.runtime.CONTROL"
# LOOM Studio (Plan B) sends "app": "studio" in every LOOM_LIVE line; demo buttons then go to it.
TARGETS = {
    "runtime": ("com.loom.runtime", "com.loom.runtime.CONTROL"),
    "studio": ("com.loom.studio", "com.loom.studio.CONTROL"),
}

# Only these commands/values can be sent to the phone.
ALLOWED = {
    "stress": {"off", "gpu", "cpu"},
    "mode": {"adaptive", "fixed"},
    "goal": {"10", "15", "20", "25"},
    "heat": {"off", "0.70", "0.75", "0.80", "0.85"},
    "pacing": {"on", "off"},
    "run": {"toggle"},
    "voice": {"on", "off"},
    "overlay": {"on", "off", "toggle"},
}

clients = []            # one queue per open browser tab
clients_lock = threading.Lock()
state = {"connected": False, "note": "starting", "last": None}
recent_events = []      # replayed to a browser tab that opens late
temps = {}              # real temperatures in °C, read from the phone over adb every few seconds


def find_adb():
    candidates = []
    for env in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(env):
            candidates.append(os.path.join(os.environ[env], "platform-tools", "adb"))
    if os.environ.get("LOCALAPPDATA"):
        candidates.append(os.path.join(os.environ["LOCALAPPDATA"], "Android", "Sdk", "platform-tools", "adb"))
    for c in candidates:
        for ext in ("", ".exe"):
            if os.path.isfile(c + ext):
                return c + ext
    return shutil.which("adb")


def broadcast(kind, payload):
    msg = json.dumps({"kind": kind, "data": payload})
    with clients_lock:
        for q in list(clients):
            try:
                q.put_nowait(msg)
            except queue.Full:
                pass


def set_status(connected, note):
    state["connected"], state["note"] = connected, note
    broadcast("status", {"connected": connected, "note": note})


class Recorder:
    def __init__(self):
        d = os.path.join(ROOT, "live_logs")
        os.makedirs(d, exist_ok=True)
        self.path = os.path.join(d, time.strftime("session_%Y%m%d_%H%M%S.jsonl"))
        self.f = open(self.path, "a", encoding="utf-8")

    def write(self, kind, data):
        self.f.write(json.dumps({"kind": kind, "data": data}) + "\n")
        self.f.flush()


def remember(kind, ev):
    recent_events.append((kind, ev))
    del recent_events[:-200]


def handle_line(line, rec):
    # Format from `adb logcat -v tag`:  I/LOOM_LIVE: {...}
    line = line.strip()
    for tag, kind in (("LOOM_LIVE", "live"), ("LOOM_EVT", "event"), ("LOOM_TRX", "transition")):
        marker = tag + ":"
        i = line.find(marker)
        if i >= 0:
            try:
                data = json.loads(line[i + len(marker):].strip())
            except ValueError:
                return
            if kind == "live":
                if temps:
                    data["temps"] = dict(temps)
                state["last"] = data
            else:
                remember(kind, data)
            rec.write(kind, data)
            broadcast(kind, data)
            return


# ---------------------------------------------------------------- real temperatures (°C)
# Read on the laptop side with adb, so the phone app needs no change:
#   dumpsys battery        -> "temperature: 352" (tenths of °C)  = battery temperature
#   dumpsys thermalservice -> "Temperature{mValue=38.1, mType=3, mName=skin, ...}" per sensor
# Android sensor types: 0 CPU, 1 GPU, 2 battery, 3 skin (the phone's surface), 9 NPU.
_T_TYPES = {0: "cpu", 1: "gpu", 2: "battery", 3: "skin", 9: "npu"}
_T_RE = re.compile(r"Temperature\{mValue=([-0-9.]+), mType=(\d+), mName=([^,]*)")


def read_temps(adb, serial):
    out = {}
    try:
        b = subprocess.run([adb, "-s", serial, "shell", "dumpsys", "battery"],
                           capture_output=True, text=True, timeout=6).stdout
        m = re.search(r"^\s*temperature:\s*(-?\d+)", b, re.M)
        if m:
            out["battery"] = int(m.group(1)) / 10.0
    except Exception:
        pass
    try:
        t = subprocess.run([adb, "-s", serial, "shell", "dumpsys", "thermalservice"],
                           capture_output=True, text=True, timeout=6).stdout
        sec = t.split("Current temperatures from HAL:", 1)
        if len(sec) == 2:
            body = sec[1].split("Current cooling devices", 1)[0]
            hot = {}
            for v, typ, _name in _T_RE.findall(body):
                k = _T_TYPES.get(int(typ))
                try:
                    v = float(v)
                except ValueError:
                    continue
                if k and 0 < v < 150:
                    hot[k] = max(v, hot.get(k, -1))
            for k, v in hot.items():
                out.setdefault(k, round(v, 1))
    except Exception:
        pass
    return out


def temp_loop(adb):
    while True:
        serial = current.get("serial")
        if serial:
            got = read_temps(adb, serial)
            temps.clear()
            temps.update(got)
        time.sleep(5)


WIFI_FILE = os.path.join(ROOT, ".phone_wifi")   # "192.168.x.y:5555" (+ optional 2nd line: phone serial)
current = {"serial": None, "link": None}
ADB_PORT = 5555


def _run(adb, *args, timeout=6):
    try:
        return subprocess.run([adb, *args], capture_output=True, text=True, timeout=timeout).stdout
    except subprocess.TimeoutExpired:
        return ""


def _listed(adb):
    out = _run(adb, "devices", timeout=8)
    rows = [l.split("\t") for l in out.splitlines()[1:] if "\t" in l]
    return [serial for serial, st in rows if st.strip() == "device"]


def _saved():
    """(address, phone serial) saved by wifi_connect.bat or by this script."""
    try:
        lines = [l.strip() for l in open(WIFI_FILE, encoding="utf-8").read().splitlines() if l.strip()]
    except OSError:
        return None, None
    return (lines[0] if lines else None), (lines[1] if len(lines) > 1 else None)


def _save(addr, serial):
    try:
        with open(WIFI_FILE, "w", encoding="utf-8") as f:
            f.write(addr + "\n" + (serial or "") + "\n")
    except OSError:
        pass


def _port_open(ip, port=ADB_PORT, timeout=0.6):
    try:
        with socket.create_connection((ip, port), timeout=timeout):
            return True
    except OSError:
        return False


def _laptop_ips():
    """This laptop's IPv4 addresses on real networks (Wi-Fi, hotspot), no loopback/link-local."""
    ips = set()
    try:
        ips.update(socket.gethostbyname_ex(socket.gethostname())[2])
    except OSError:
        pass
    for probe in ("8.8.8.8", "192.168.0.1", "10.0.0.1"):   # UDP connect sends nothing; it just picks a route
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect((probe, 80))
            ips.add(s.getsockname()[0])
            s.close()
        except OSError:
            pass
    return [ip for ip in ips if not ip.startswith(("127.", "169.254.", "0."))]


def _try_connect(adb, addr, want_serial=None):
    """adb connect to addr; True if it becomes a working device (and is our phone, if known)."""
    ip = addr.split(":")[0]
    if not _port_open(ip, int(addr.split(":")[1])):
        return False
    _run(adb, "disconnect", addr, timeout=5)      # a stale "offline" entry never recovers by itself
    _run(adb, "connect", addr, timeout=6)
    time.sleep(0.8)
    if addr not in _listed(adb):
        return False
    if want_serial:
        got = _run(adb, "-s", addr, "shell", "getprop", "ro.serialno", timeout=5).strip()
        if got and got != want_serial:
            _run(adb, "disconnect", addr, timeout=5)
            return False
    return True


def _scan_for_phone(adb, want_serial):
    """The phone's Wi-Fi address changed (new DHCP lease, other network). Look for it:
    1) Android 11+ Wireless debugging announces itself over mDNS;
    2) any host on this laptop's local /24 networks with adb's port 5555 open."""
    for line in _run(adb, "mdns", "services", timeout=6).splitlines():
        m = re.search(r"(\d+\.\d+\.\d+\.\d+:\d+)", line)
        if m and "_adb" in line and _try_connect(adb, m.group(1), want_serial):
            return m.group(1)
    mine = _laptop_ips()
    hosts = []
    for ip in mine:
        base = ip.rsplit(".", 1)[0]
        hosts += [f"{base}.{i}" for i in range(1, 255) if f"{base}.{i}" not in mine]
    found = []
    lock = threading.Lock()

    def worker(chunk):
        for h in chunk:
            if _port_open(h, timeout=0.35):
                with lock:
                    found.append(h)
    chunks = [hosts[i::64] for i in range(64)]
    ts = [threading.Thread(target=worker, args=(c,), daemon=True) for c in chunks if c]
    for t in ts:
        t.start()
    for t in ts:
        t.join()
    for h in found:
        if _try_connect(adb, f"{h}:{ADB_PORT}", want_serial):
            return f"{h}:{ADB_PORT}"
    return None


def _arm_wifi_from_usb(adb, usb_serial):
    """Phone is on USB: switch adb to network mode and remember its CURRENT Wi-Fi address,
    so pulling the cable later continues over Wi-Fi with no script to run."""
    out = _run(adb, "-s", usb_serial, "shell", "ip", "-f", "inet", "addr", "show", "wlan0")
    m = re.search(r"inet (\d+\.\d+\.\d+\.\d+)", out)
    if not m:
        return None
    addr = f"{m.group(1)}:{ADB_PORT}"
    phone_serial = _run(adb, "-s", usb_serial, "shell", "getprop", "ro.serialno").strip() or usb_serial
    if not _port_open(m.group(1)):
        _run(adb, "-s", usb_serial, "tcpip", str(ADB_PORT), timeout=8)
        time.sleep(3)
    if _try_connect(adb, addr):
        _save(addr, phone_serial)
        return addr
    return None


def pick_device(adb):
    """USB first, then Wi-Fi. Heals itself:
    - phone on USB -> network mode is (re)armed and its current Wi-Fi address saved;
    - saved Wi-Fi address not answering -> search the local network for the phone."""
    devs = _listed(adb)
    usb = [d for d in devs if ":" not in d]
    if usb:
        pick_device.unreachable = None
        now = time.time()
        if now - getattr(pick_device, "armed_at", 0) > 60:
            pick_device.armed_at = now
            threading.Thread(target=_arm_wifi_from_usb, args=(adb, usb[0]), daemon=True).start()
        return usb[0], "USB"
    if devs:
        return devs[0], "Wi-Fi"

    addr, want = _saved()
    if addr and _try_connect(adb, addr, want):
        pick_device.unreachable = None
        return addr, "Wi-Fi"

    now = time.time()
    if now - getattr(pick_device, "scanned_at", 0) > 15:
        pick_device.scanned_at = now
        set_status(False, "Looking for the phone on this Wi-Fi network…")
        found = _scan_for_phone(adb, want)
        if found:
            _save(found, want)
            pick_device.unreachable = None
            return found, "Wi-Fi"
    pick_device.unreachable = addr
    return None, None


def logcat_loop(adb, rec):
    while True:
        try:
            serial, link = pick_device(adb)
            if not serial:
                far = getattr(pick_device, "unreachable", None)
                if far:
                    set_status(False, f"Phone not found on Wi-Fi (last at {far}). Still searching… "
                                      "Check: phone Wi-Fi on and on the SAME network as the laptop. "
                                      "Quick fix: plug in USB for 10 s, then unplug.")
                else:
                    hint = " or run wifi_connect.bat once" if not os.path.isfile(WIFI_FILE) else \
                        " (Wi-Fi: same network? phone Wi-Fi on?)"
                    set_status(False, "No phone found — plug in the iQOO with USB debugging on" + hint)
                time.sleep(3)
                continue
            current["serial"], current["link"] = serial, link
            set_status(True, f"Connected over {link} · waiting for LOOM (open the app)")
            proc = subprocess.Popen(
                [adb, "-s", serial, "logcat", "-v", "tag", "-T", "1", "LOOM_LIVE:I", "LOOM_EVT:I", "LOOM_TRX:I", "*:S"],
                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, encoding="utf-8",
                errors="replace", bufsize=1,
            )
            live_note = f"Live from the phone ({link})"
            for line in proc.stdout:
                if state["note"] != live_note:
                    set_status(True, live_note)
                handle_line(line, rec)
            proc.wait()
            current["serial"] = None
            set_status(False, "Phone disconnected — reconnecting…")
        except FileNotFoundError:
            set_status(False, "adb not found — install Android SDK platform-tools")
            time.sleep(5)
        except Exception as e:  # keep the dashboard alive whatever happens
            set_status(False, f"adb error: {e}")
        time.sleep(2)


def demo_loop(rec):
    """Replays tools/demo_session.jsonl: a session recorded from LOOM's REAL control code running
    on a simulated phone (fake 30 FPS camera, latencies measured on the iQOO, heat model).
    Timestamps are shifted to 'now' so the page behaves live. Labelled SIMULATED on the page."""
    path = os.path.join(HERE, "demo_session.jsonl")
    with open(path, encoding="utf-8") as f:
        items = [json.loads(l) for l in f if l.strip()]
    set_status(True, "SIMULATED PHONE — replay of LOOM's real control code (not a live phone)")
    while True:
        start = time.time()
        for it in items:
            delay = start + it["at_ms"] / 1000.0 - time.time()
            if delay > 0:
                time.sleep(delay)
            data = dict(it["data"])
            data["t"] = int(time.time() * 1000)
            kind = it["kind"]
            if kind == "live":
                state["last"] = data
            else:
                remember(kind, data)
            rec.write(kind, data)
            broadcast(kind, data)
        time.sleep(3)
        recent_events.clear()
        broadcast("reset", {})




class Handler(BaseHTTPRequestHandler):
    adb = None
    demo = False

    def log_message(self, *a):
        pass

    def _send(self, code, body, ctype="application/json"):
        b = body.encode("utf-8") if isinstance(body, str) else body
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(b)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(b)

    def do_GET(self):
        static = {
            "/": ("index.html", "text/html; charset=utf-8"),
            "/index.html": ("index.html", "text/html; charset=utf-8"),
            "/app.js": ("app.js", "application/javascript; charset=utf-8"),
            "/app.css": ("app.css", "text/css; charset=utf-8"),
            "/loom-logo.png": ("loom-logo.png", "image/png"),
            "/favicon.svg": ("favicon.svg", "image/svg+xml"),
        }
        if self.path in static:
            name, ctype = static[self.path]
            path = os.path.join(HERE, "dashboard", "dist", name)
            if not os.path.isfile(path):
                return self._send(500, "React build missing: run `npm run build` in tools/dashboard", "text/plain")
            with open(path, "rb") as f:
                self._send(200, f.read(), ctype)
        elif self.path == "/stream":
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            q = queue.Queue(maxsize=500)
            with clients_lock:
                clients.append(q)
            try:
                first = json.dumps({"kind": "status", "data": {"connected": state["connected"], "note": state["note"]}})
                self.wfile.write(f"data: {first}\n\n".encode())
                for kind, ev in list(recent_events):
                    self.wfile.write(f"data: {json.dumps({'kind': kind, 'data': ev})}\n\n".encode())
                if state["last"]:
                    self.wfile.write(f"data: {json.dumps({'kind': 'live', 'data': state['last']})}\n\n".encode())
                self.wfile.flush()
                while True:
                    try:
                        msg = q.get(timeout=15)
                        self.wfile.write(f"data: {msg}\n\n".encode())
                    except queue.Empty:
                        self.wfile.write(b": keep-alive\n\n")
                    self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError, OSError):
                pass
            finally:
                with clients_lock:
                    if q in clients:
                        clients.remove(q)
        else:
            self._send(404, '{"error":"not found"}')

    def do_POST(self):
        if self.path != "/cmd":
            return self._send(404, '{"error":"not found"}')
        try:
            n = int(self.headers.get("Content-Length", "0"))
            body = json.loads(self.rfile.read(n) or b"{}")
            cmd, value = str(body.get("cmd", "")), str(body.get("value", ""))
        except ValueError:
            return self._send(400, '{"error":"bad request"}')
        if cmd not in ALLOWED or value not in ALLOWED[cmd]:
            return self._send(400, '{"error":"command not allowed"}')
        if self.demo:
            return self._send(200, '{"ok":false,"error":"simulated replay: controls work only with a phone connected"}')
        if not self.adb:
            return self._send(503, '{"error":"adb not found"}')
        if not current["serial"]:
            return self._send(503, '{"ok":false,"error":"no phone connected"}')
        last = state.get("last") or {}
        package, action = TARGETS.get(last.get("app", "runtime"), (PACKAGE, ACTION))
        r = subprocess.run(
            [self.adb, "-s", current["serial"], "shell", "am", "broadcast", "-a", action, "-p", package,
             "--es", "cmd", cmd, "--es", "value", value],
            capture_output=True, text=True, timeout=10,
        )
        ok = r.returncode == 0 and "Broadcast completed" in r.stdout
        self._send(200 if ok else 502, json.dumps({"ok": ok, "out": (r.stdout + r.stderr).strip()[-300:]}))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--demo", action="store_true", help="simulated data, no phone")
    ap.add_argument("--no-browser", action="store_true")
    a = ap.parse_args()

    rec = Recorder()
    Handler.demo = a.demo
    if a.demo:
        threading.Thread(target=demo_loop, args=(rec,), daemon=True).start()
    else:
        adb = find_adb()
        Handler.adb = adb
        if not adb:
            set_status(False, "adb not found — install Android SDK platform-tools")
        else:
            threading.Thread(target=logcat_loop, args=(adb, rec), daemon=True).start()
            threading.Thread(target=temp_loop, args=(adb,), daemon=True).start()

    srv = ThreadingHTTPServer(("127.0.0.1", a.port), Handler)
    url = f"http://localhost:{a.port}"
    print(f"LOOM live dashboard: {url}   (session log: {rec.path})")
    print("Press Ctrl+C to stop.")
    if not a.no_browser:
        threading.Timer(1.0, lambda: webbrowser.open(url)).start()
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    sys.exit(main())
