#!/usr/bin/env python3
"""A fake Android TV or Kodi box on this Mac, to test the iPhone link without a real box.

Usage: mock-box.py atv2|atv1|kodi|webos DIR

  atv2  Google TV remote protocol version 2: pairing (TLS 6467, protobuf, 6-character code) and remote (TLS 6466)
  atv1  version 1 (older Android TV, Ugoos): pairing in JSON with a 4-character code, remote in [1, type, len] frames
  kodi  Kodi JSON-RPC (TCP 9090) and EventServer (UDP 9777)
  webos an LG TV: SSAP over a TLS WebSocket on 3001 only (like newer firmware), the prompt, the pointer socket,
        and Wake-on-LAN (UDP 19009 here: port 9 needs root)

DIR: the client writes its certificate to DIR/client.der (the box asks for it and checks it, like a real box does
after pairing). The box writes the pairing code to DIR/code.txt (a real box shows it on the TV) and logs what it
receives to DIR/log.txt. ios/scripts/test-link.sh runs it with the integration test.
"""
import hashlib
import json
import os
import re
import socket
import ssl
import struct
import subprocess
import sys
import threading

MODE, DIR = sys.argv[1], sys.argv[2]
HOST = "127.0.0.1"
lock = threading.Lock()


def log(line):
    with lock:
        print(line, flush=True)
        with open(os.path.join(DIR, "log.txt"), "a") as f:
            f.write(line + "\n")


# ---- certificates ----

def make_server_cert():
    key, crt = os.path.join(DIR, "server.key"), os.path.join(DIR, "server.pem")
    if not os.path.exists(crt):
        subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", key, "-out", crt,
                        "-days", "30", "-subj", "/CN=mockbox"], check=True, capture_output=True)
    return key, crt


def rsa_numbers(der_or_pem, form):
    """Modulus and exponent of a certificate's RSA key, as bytes without leading zeros."""
    text = subprocess.run(["openssl", "x509", "-inform", form, "-noout", "-text"], input=der_or_pem,
                          capture_output=True, check=True).stdout.decode()
    mod = re.search(r"Modulus:\s*((?:[0-9a-f]{2}:?\s*)+)", text).group(1)
    mod = bytes.fromhex(re.sub(r"[^0-9a-f]", "", mod)).lstrip(b"\x00")
    exp = int(re.search(r"Exponent: (\d+)", text).group(1))
    return mod, exp.to_bytes((exp.bit_length() + 7) // 8, "big")


def tls_server(sock):
    """TLS that asks for the client certificate and accepts only the one in DIR/client.der."""
    key, crt = make_server_cert()
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(crt, key)
    ctx.verify_mode = ssl.CERT_REQUIRED
    ctx.load_verify_locations(cadata=open(os.path.join(DIR, "client.der"), "rb").read())
    return ctx.wrap_socket(sock, server_side=True)


# ---- protobuf, just enough ----

def varint(n):
    out = b""
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out += bytes([b | 0x80])
        else:
            return out + bytes([b])


def pb(*fields):
    """pb((1, 5), (2, b"bytes"), (3, pb(...))): ints are varints, bytes are length-delimited."""
    out = b""
    for field, value in fields:
        if isinstance(value, int):
            out += varint(field << 3) + varint(value)
        else:
            out += varint(field << 3 | 2) + varint(len(value)) + value
    return out


def parse(b):
    fields, i = {}, 0
    while i < len(b):
        tag, i = read_varint_bytes(b, i)
        f, wire = tag >> 3, tag & 7
        if wire == 0:
            fields[f], i = read_varint_bytes(b, i)
        elif wire == 2:
            n, i = read_varint_bytes(b, i)
            fields[f], i = b[i:i + n], i + n
        else:
            raise ValueError("wire type %d" % wire)
    return fields


def read_varint_bytes(b, i):
    n, shift = 0, 0
    while True:
        c = b[i]
        i += 1
        n |= (c & 0x7F) << shift
        shift += 7
        if not c & 0x80:
            return n, i


def recv_exact(s, n):
    data = b""
    while len(data) < n:
        chunk = s.recv(n - len(data))
        if not chunk:
            raise EOFError
        data += chunk
    return data


def read_frame(s):
    n, shift = 0, 0
    while True:
        c = recv_exact(s, 1)[0]
        n |= (c & 0x7F) << shift
        shift += 7
        if not c & 0x80:
            return recv_exact(s, n)


def send_frame(s, body):
    s.sendall(varint(len(body)) + body)


# ---- pairing (6467) ----

def secret_for(s, check_bytes):
    client = s.getpeercert(binary_form=True)
    cm, ce = rsa_numbers(client, "der")
    sm, se = rsa_numbers(open(make_server_cert()[1], "rb").read(), "pem")
    return hashlib.sha256(cm + ce + sm + se + check_bytes).digest()


def pairing(conn):
    s = tls_server(conn)
    first = read_frame(s)   # every client starts with a version-2 request
    if MODE == "atv1":
        # A version-1 box answers in JSON with a 4-byte length: the client starts again in version 1
        json_send(s, {"protocol_version": 1, "status": 400})
        s.close()
        return
    log("pairing v2 request " + str(parse(first).get(10, b"")[:40]))
    outer = lambda *f: pb((1, 2), (2, 200), *f)
    send_frame(s, outer((11, b"")))   # request ack
    expected = None
    while True:
        m = parse(read_frame(s))
        if 20 in m:
            send_frame(s, outer((20, pb((1, pb((1, 3), (2, 6))), (3, 1)))))
        elif 30 in m:
            suffix = os.urandom(2)
            expected = secret_for(s, suffix)
            code = "%02X" % expected[0] + suffix.hex().upper()
            open(os.path.join(DIR, "code.txt"), "w").write(code)
            log("pairing shows code " + code)
            send_frame(s, outer((31, b"")))
        elif 40 in m:
            got = parse(m[40]).get(1)
            ok = got == expected
            log("pairing secret " + ("ok" if ok else "WRONG"))
            send_frame(s, outer((41, b"")) if ok else pb((1, 2), (2, 402)))
            s.close()
            return


def json_send(s, obj):
    b = json.dumps(obj).encode()
    s.sendall(struct.pack(">I", len(b)) + b)


def json_read(s):
    n = struct.unpack(">I", recv_exact(s, 4))[0]
    return json.loads(recv_exact(s, n))


def pairing_v1(conn):
    s = tls_server(conn)
    expected = None
    while True:
        m = json_read(s)
        t = m.get("type")
        if t == 10:
            log("pairing v1 request " + m["payload"].get("client_name", ""))
            json_send(s, {"protocol_version": 1, "payload": {}, "type": 11, "status": 200})
        elif t == 20:
            json_send(s, {"protocol_version": 1, "payload": {"preferred_role": 1}, "type": 20, "status": 200})
        elif t == 30:
            suffix = os.urandom(1)
            expected = secret_for(s, suffix)
            code = "%02X" % expected[0] + suffix.hex().upper()
            open(os.path.join(DIR, "code.txt"), "w").write(code)
            log("pairing shows code " + code)
            json_send(s, {"protocol_version": 1, "payload": {}, "type": 31, "status": 200})
        elif t == 40:
            import base64
            ok = base64.b64decode(m["payload"]["secret"]) == expected
            log("pairing secret " + ("ok" if ok else "WRONG"))
            json_send(s, {"protocol_version": 1, "payload": {}, "type": 41, "status": 200 if ok else 402})
            s.close()
            return


def pairing_any(conn):
    """Version 1 boxes answer the first (version 2) attempt, then talk JSON on the second connection."""
    conn.settimeout(10)
    if MODE == "atv1" and getattr(pairing_any, "tried", False):
        return pairing_v1(conn)
    pairing_any.tried = True
    return pairing(conn)


# ---- remote (6466) ----

def remote_v2(conn):
    s = tls_server(conn)
    send_frame(s, pb((1, pb((1, 622), (2, pb((1, b"MockTV"), (2, b"mock"), (3, 1), (4, b"1"), (5, b"mock"), (6, b"1")))))))
    m = parse(read_frame(s))
    info = parse(parse(m[1])[2])
    log("remote v2 configure from " + info[1].decode() + " app " + info[2].decode())
    send_frame(s, pb((2, pb((1, 622)))))
    parse(read_frame(s))   # set active reply
    send_frame(s, pb((8, pb((1, 7)))))   # a ping
    while True:
        m = parse(read_frame(s))
        if 9 in m:
            log("remote v2 pong %d" % parse(m[9]).get(1, 0))
        elif 10 in m:
            k = parse(m[10])
            log("key %d" % k[1])


def remote_v1(conn):
    s = tls_server(conn)
    s.settimeout(30)
    while True:
        version, kind, n = struct.unpack(">BBH", recv_exact(s, 4))
        payload = recv_exact(s, n)
        if kind == 0:     # configure
            name = b"AM9 PRO"
            ack = b"\x00\x00\x00\x01" + bytes([5]) + b"Mock " + bytes([len(name)]) + name
            s.sendall(struct.pack(">BBH", 1, 7, len(ack)) + ack)
            s.sendall(bytes([1, 20, 0, 0]))   # ping
        elif kind == 2:   # key: counter, up/down, code
            _, up, code = struct.unpack(">qii", payload)
            if up == 0:
                log("key %d" % code)
        elif kind == 21:
            log("remote v1 pong")
        elif kind == 19:
            log("interactive %d" % payload[0])
            if payload[0] == 1:
                show_ime(s, "abc")
        elif kind == 18:  # IME call
            call = payload[0]
            if call == 3:
                ln = struct.unpack(">i", payload[2:6])[0]
                log("commit " + payload[6:6 + ln].decode())
            elif call == 4:
                log("delete %d" % struct.unpack(">i", payload[1:5])[0])
            elif call == 1:
                log("editor action %d" % struct.unpack(">i", payload[1:5])[0])


def v1_string(s):
    b = s.encode()
    return b"\x00" + struct.pack(">i", len(b)) + b


def show_ime(s, text):
    """ShowIME: EditorInfo, then ExtractedText with the text already in the field."""
    p = struct.pack(">ii", 1, 3)                       # inputType, imeOptions (IME_ACTION_SEARCH)
    p += v1_string("") + v1_string("")                 # privateImeOptions, actionLabel
    p += struct.pack(">iiii", 0, 0, 0, 0)              # actionId, selStart, selEnd, capsMode
    p += v1_string("") + v1_string("") + v1_string("mock")  # hint, label, packageName
    p += struct.pack(">i", 1) + v1_string("search")    # fieldId, fieldName
    p += b"\x00\x7f" + b"\x00" + v1_string(text)       # a flag, 127, the text
    s.sendall(struct.pack(">BBH", 1, 5, len(p)) + p)


# ---- Kodi ----

def kodi(conn):
    buf = b""
    while True:
        chunk = conn.recv(4096)
        if not chunk:
            return
        buf += chunk
        dec = json.JSONDecoder()
        text = buf.decode()
        while text:
            try:
                obj, end = dec.raw_decode(text)
            except ValueError:
                break
            text = text[end:].lstrip()
            method = obj.get("method", "")
            params = obj.get("params")
            log("kodi " + method + (" " + json.dumps(params, sort_keys=True) if params else ""))
            if method == "XBMC.GetInfoLabels":
                reply = {"id": obj.get("id"), "jsonrpc": "2.0",
                         "result": {"System.FriendlyName": os.environ.get("MOCK_KODI_NAME", "MockELEC")}}
            else:
                reply = {"id": obj.get("id"), "jsonrpc": "2.0", "result": "OK"}
            conn.sendall(json.dumps(reply).encode())
            if method == "Input.Home":   # a search field opens its keyboard
                note = {"jsonrpc": "2.0", "method": "Input.OnInputRequested",
                        "params": {"data": {"title": "Search", "type": "keyboard", "value": "xyz"}, "sender": "xbmc"}}
                conn.sendall(json.dumps(note).encode())
        buf = text.encode()


def event_server():
    u = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    u.bind((HOST, 9777))
    while True:
        data, _ = u.recvfrom(2048)
        if data[:4] == b"XBMC":
            kind = struct.unpack(">H", data[6:8])[0]
            log("eventserver %s" % {1: "helo", 4: "mouse"}.get(kind, kind))


# ---- LG webOS ----

WS_GUID = b"258EAFA5-E914-47DA-95CA-C5AB0DC85B11"   # RFC 6455
WEBOS_KEY = "mock-client-key"


def ws_accept(s):
    """HTTP upgrade. Returns the request path."""
    head = b""
    while b"\r\n\r\n" not in head:
        chunk = s.recv(1)
        if not chunk:
            raise EOFError
        head += chunk
    lines = head.decode().split("\r\n")
    path = lines[0].split(" ")[1]
    key = next(l.split(":", 1)[1].strip() for l in lines if l.lower().startswith("sec-websocket-key"))
    accept = __import__("base64").b64encode(hashlib.sha1(key.encode() + WS_GUID).digest()).decode()
    s.sendall(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
               "Sec-WebSocket-Accept: %s\r\n\r\n" % accept).encode())
    return path


def ws_read(s):
    """One text message (pings answered, control frames skipped)."""
    while True:
        b0, b1 = recv_exact(s, 2)
        op, n = b0 & 0x0F, b1 & 0x7F
        if n == 126:
            n = struct.unpack(">H", recv_exact(s, 2))[0]
        elif n == 127:
            n = struct.unpack(">Q", recv_exact(s, 8))[0]
        mask = recv_exact(s, 4) if b1 & 0x80 else b"\0\0\0\0"
        data = bytes(c ^ mask[i % 4] for i, c in enumerate(recv_exact(s, n)))
        if op == 8:
            raise EOFError
        if op == 9:
            ws_send(s, data, 10)
        elif op == 1:
            return data.decode()


def ws_send(s, data, op=1):
    if isinstance(data, str):
        data = data.encode()
    n = len(data)
    head = bytes([0x80 | op]) + (bytes([n]) if n < 126 else bytes([126]) + struct.pack(">H", n))
    with lock:
        s.sendall(head + data)


def webos(conn):
    key, crt = make_server_cert()
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(crt, key)
    s = ctx.wrap_socket(conn, server_side=True)
    path = ws_accept(s)
    if path.endswith("netinput.pointer.sock"):
        while True:
            msg = dict(l.split(":", 1) for l in ws_read(s).strip().split("\n"))
            kind = msg.pop("type")
            if kind == "button":
                log("button " + msg["name"])
                if msg["name"] == "HOME" and keyboard_sub:
                    main_sock, sub_id = keyboard_sub[-1]
                    ws_send(main_sock, json.dumps({"type": "response", "id": sub_id,
                                                   "payload": {"currentWidget": {"focus": True}}}))
            elif kind == "move":
                log("move %s %s" % (msg["dx"], msg["dy"]))
            else:
                log(kind + (" " + msg["dy"] if "dy" in msg else ""))
    while True:
        m = json.loads(ws_read(s))
        mid, uri, payload = m.get("id"), m.get("uri", ""), m.get("payload", {})
        reply = lambda p, t="response": ws_send(s, json.dumps({"type": t, "id": mid, "payload": p}))
        if m.get("type") == "register":
            if payload.get("client-key") == WEBOS_KEY:
                log("register with key")
                reply({"client-key": WEBOS_KEY}, "registered")
            else:
                log("register: prompt on the TV")
                reply({"pairingType": "PROMPT", "returnValue": True})
                threading.Timer(1.0, lambda: (log("prompt accepted"),
                                              reply({"client-key": WEBOS_KEY}, "registered"))).start()
            continue
        ep = uri.replace("ssap://", "")
        if ep == "system/getSystemInfo":
            reply({"returnValue": True, "modelName": "OLED65G5PUA"})
        elif ep == "com.webos.service.networkinput/getPointerInputSocket":
            # Newer firmware names it with ws:// and 3000 even when only wss:// on 3001 works
            reply({"returnValue": True, "socketPath": "ws://%s:3000/resources/mock/netinput.pointer.sock" % HOST})
        elif ep == "com.webos.service.connectionmanager/getinfo":
            reply({"returnValue": True, "wiredInfo": {"macAddress": "aa:bb:cc:dd:ee:ff"},
                   "wifiInfo": {"macAddress": "00:00:00:00:00:00"}})
        elif ep == "com.webos.service.tvpower/power/getPowerState":
            reply({"returnValue": True, "state": "Active"})
        elif ep == "com.webos.media/getForegroundAppInfo":
            reply({"returnValue": True, "subscribed": True, "foregroundAppInfo": [{"playState": "playing"}]})
        elif ep == "com.webos.service.ime/registerRemoteKeyboard":
            keyboard_sub.append((s, mid))
            reply({"returnValue": True, "subscribed": True})
        elif ep == "com.webos.service.ime/insertText":
            log("insert " + payload["text"])
            reply({"returnValue": True})
        elif ep == "com.webos.service.ime/deleteCharacters":
            log("delete %d" % payload["count"])
            reply({"returnValue": True})
        elif ep == "com.webos.service.ime/sendEnterKey":
            log("enter")
            reply({"returnValue": True})
        elif ep == "system/turnOff":
            log("turn off")
            reply({"returnValue": True})
        else:
            ws_send(s, json.dumps({"type": "error", "id": mid, "error": "404 no such service or method",
                                   "payload": {}}))


keyboard_sub = []
WOL_PORT = 19009   # the test sends here: port 9 needs root on macOS


def wake_on_lan():
    u = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    u.bind((HOST, WOL_PORT))
    while True:
        data, _ = u.recvfrom(256)
        if len(data) == 102 and data[:6] == b"\xff" * 6:
            log("wol " + ":".join("%02x" % c for c in data[6:12]))


# ---- listeners ----

def serve(port, handler):
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((HOST, port))
    srv.listen(8)

    def loop():
        while True:
            conn, _ = srv.accept()

            def run(c=conn):
                try:
                    handler(c)
                except (EOFError, ConnectionError, ssl.SSLError, OSError) as e:
                    log("closed on %d: %s" % (port, type(e).__name__))
                finally:
                    c.close()
            threading.Thread(target=run, daemon=True).start()
    threading.Thread(target=loop, daemon=True).start()


if __name__ == "__main__":
    os.makedirs(DIR, exist_ok=True)
    open(os.path.join(DIR, "log.txt"), "w").close()
    if MODE in ("atv1", "atv2"):
        make_server_cert()
        serve(6467, pairing_any)
        serve(6466, remote_v1 if MODE == "atv1" else remote_v2)
    elif MODE == "webos":
        make_server_cert()
        serve(3001, webos)
        threading.Thread(target=wake_on_lan, daemon=True).start()
    else:
        serve(9090, kodi)
        threading.Thread(target=event_server, daemon=True).start()
    log("mock box (%s) on %s" % (MODE, HOST))
    threading.Event().wait()
