# Phonalyser net protocol - v1

Network bridge between a headless Phonalyser server (the machine wired to the
measurement hardware) and Phonalyser GUI clients (desktop Java app, web app).
The server owns the local audio backends (sound cards, QA40x) and the signal
generator; clients stream captured audio down and send control up.

Transport summary:

| Plane      | Transport                          | Encoding |
|------------|------------------------------------|----------|
| Discovery  | UDP multicast beacon               | JSON, UTF-8 |
| Info       | HTTP GET (stateless)               | JSON |
| Control    | WebSocket text frames              | JSON, UTF-8 |
| Audio data | WebSocket binary frames (same socket) | fixed 16-byte header + payload, little-endian |
| File upload| HTTP PUT                           | raw bytes |

**One port, one number.** Everything is served on a single port, default
**8377** (`--port`): `http://host:8377/info` and `ws://host:8377/` are the same
listener, and discovery multicasts to 8377/**udp** (§2.1). A WebSocket upgrade
at `/` becomes a control channel; any other request is served as HTTP, so
`GET /` still returns the web bundle. The wire therefore carries exactly one
`port` field - two numbers would be two firewall rules, two fields in every
beacon and remembered server, and a browser that loaded the bundle from one of
them would have to be told the other.

Design rules: reliable ordered transport only (a measurement gap is data
corruption, not an inconvenience - no UDP for audio); WebSocket because the web
client has nothing else and one server implementation must serve both GUIs;
JSON control because it is debuggable and both sides parse it natively; all
binary data little-endian (QA40x native order, JS `DataView` friendly).

The server ships as its own fat jar, `phonalyser-server-<version>-<platform>.jar`
(`java -jar ...`), built from the `server-net` module - it carries no SWT and no
GUI code at all, so no Display can be initialised even by accident.

---

## 1. Versioning

- `PROTO = 1` - a single integer. Any change that breaks an existing peer bumps
  it; additive features are announced in the `caps` list instead.
- **Version negotiation** (designed for backwards compatibility from day one):
  the client's `hello` carries the range it speaks - `proto` (highest) and
  `protoMin` (lowest; absent = same as `proto`). The server picks the highest
  version inside both ranges; the `hello` response's `proto` field is the
  **chosen** version and governs the whole session. No overlap -> error
  `PROTO_MISMATCH` (message names both ranges) and close. A future v2 client
  therefore still talks v1 to a v1 server, and a v2 server still serves v1
  clients - no flag day. Beacons and `/info` advertise the server's highest
  version.
- Unknown JSON fields MUST be ignored by both sides (forward compatibility).
  Unknown message types answer error `UNSUPPORTED`. Unknown binary frame types
  are skipped whole - the WebSocket message boundary delimits the frame, so
  the receiver discards the entire message (`n` is type-specific and is a
  payload length for PCM only, §5).

## 2. Discovery

### 2.1 Multicast beacon (server -> LAN)

- Group `239.255.83.77`, port **8377/udp**, TTL 1, sent every **500 ms** and
  once immediately at start-up.  UDP and TCP are independent port spaces, so
  this is the same number the server listens on - one port to remember, one to
  open in a firewall.
- The group port is **fixed** and is NOT moved by `--port`: discovery only works
  if every client binds the port every server announces on.  A server started on
  a custom TCP port still beacons to 8377/udp and carries its real port in the
  payload, which is what a client dials.
- The payload is identity and the port only, all fixed
  for the server's lifetime - device changes are told to CONNECTED clients
  through `ev.devices.changed`, never to the LAN (v1.1 doc fix: the old
  "on any change (name, ports, device summary)" promised a re-announce
  nothing carries).
- Payload - one JSON datagram:

```json
{ "phonalyser": 1, "proto": 1, "serverId": "b7e0...-uuid", "name": "Bench QA403",
  "app": "1.2.0", "port": 8377 }
```

- `name` is the operator-configured text name (`--name`, default: host name).
  `serverId` is a UUID generated once per server installation - clients key
  their remembered-server list on it, never on the IP.
- The receiver takes the server's IP from the datagram source address - the
  payload deliberately carries no address (multi-homed hosts lie).
- Probe: a client MAY send the single byte `?` (0x3F) to the group; every
  server answers with one immediate beacon (unicast, to the probe's source).
  This makes a "Scan" button instant (unicast, no wait for the next beacon).

### 2.2 Peer list (for clients that cannot multicast - the web app)

Every server also *listens* on the beacon group and keeps a peer table (entries
expire after 4 missed intervals = 2 s - same threshold philosophy as the
keepalive). `GET /servers` returns the table,
self included:

```json
{ "servers": [ { "serverId": "...", "name": "Bench QA403", "host": "192.168.1.40",
                 "port": 8377, "app": "1.2.0", "proto": 1,
                 "self": true } ] }
```

A web client therefore needs exactly one reachable address (typed once, then
remembered) to see every server on the LAN.

## 3. HTTP endpoints

Default port **8377** (`--port`) - the same port serves HTTP and the WebSocket
upgrade at `/` (§4), so a plain `GET /` is the web bundle and an upgrade request
on that same URL is a control channel. All responses `application/json` unless
noted; permissive CORS (`Access-Control-Allow-Origin: *`). No authentication
in v1 - this is a LAN instrument protocol; `--bind` restricts the interface.

| Endpoint | Meaning |
|---|---|
| `GET /info`    | `{serverId, name, app, proto, os, uptimeS, port}` |
| `GET /devices` | Same device list as `devices.list` (§4.3), lock state included - for curl debugging |
| `GET /servers` | Peer table (§2.2) |
| `GET /health`  | `200 {"ok":true}` |
| `PUT /files`   | File upload for remote playback. Raw body, limit **50 MB** (`413` above); the server also bounds the store as a whole and answers `413` when it is full. Response `{"fileId":"f-1","bytes":N}`. Files live in server RAM, owned by nobody until referenced by `gen.playFile`, and are dropped when the referencing connection closes or on `DELETE /files/{id}`, which answers `200 {"ok":true}` (v1.1: documented - same body as `/health`). **`Content-Type` (v1.1):** the client sends the file's audio MIME type - `audio/wav`, `audio/flac`, `audio/aiff` - and the server stores it beside the bytes and picks the decoder from it at `gen.playFile`. A content sniff is the fallback, used only when the header is absent (`application/octet-stream` included) or names a type this server does not know; it is what every upload relied on before the type travelled. Declaring the type is what makes a FLAC behind an ID3 tag and an AIFF decodable at all, neither being reliably distinguishable from its first bytes. An unrecognised type is **not** an upload error: the upload succeeds and the decode fails at `gen.playFile` with the existing `BAD_REQUEST`, so one refusal path covers every undecodable file. |
| `GET /`        | The static web app bundle (when built with it). Served over plain HTTP the page is **not a secure context**: `getUserMedia`/WebUSB do not exist there, so the served app offers the net backend only. |

**Refused requests carry a code.** Any non-2xx answer from the JSON endpoints
(`/info`, `/devices`, `/servers`, `/health`, `/files`) has the body
`{"ok":false,"error":{"code":"...","message":"..."}}`, with `code` from the §4.2
vocabulary - `FILE_TOO_LARGE` for a `413`, `NO_SUCH_FILE` for a `DELETE` of an
unknown handle, `UNSUPPORTED` for a `405`, `INTERNAL` for a `500`. A bare
`{"ok":false}` would make every refusal indistinguishable by payload, and
`FILE_TOO_LARGE` exists precisely so an over-cap upload is not guesswork.
(`GET /` is a plain static route and answers a text `404`.)

Mixed-content note (browser fact, not our choice): a page loaded over `https://`
may not open `ws://` to a LAN host - only `wss://` or `ws://localhost`. The net
backend is still offered in an https-served app, but connecting to a non-local
server will be blocked by the browser; the supported web route is loading the
app from the server itself (`GET /`).

## 4. Control channel (WebSocket upgrade at `/`, default port 8377)

### 4.0 Message envelope

- Client requests: `{"t":"<type>", "id":<int>, ...}` - `id` is a per-connection
  monotonically increasing integer.
- Responses: `{"t":"resp", "id":<same>, "ok":true, "data":{...}}` or
  `{"t":"resp", "id":<same>, "ok":false, "error":{"code":"DEVICE_LOCKED",
  "message":"...", "by":"Developer's laptop"}}`.
- Server-initiated events: `{"t":"ev.<name>", ...}` - no `id`, never answered.
- The server also sends *requests* (only `ping`); the client answers with the
  same `resp` form. Server request ids are negative to avoid collision.

### 4.1 Session

| Type | Direction | Fields | Response data |
|---|---|---|---|
| `hello` | c->s, MUST be first | `proto` (highest supported), `protoMin` (lowest; optional), `client` (app+version string), `name` (user-visible client name, shown in lock info) | `proto` (**chosen** session version, §1), `serverId`, `name`, `app`, `caps:["qa40x","gen","files"]` |
| `ping` | both, every **500 ms** | - | `{}` |
| `bye`  | c->s | - | `{}`; server releases everything, closes |

**Keepalive contract:** each side sends `ping` every 500 ms and counts
unanswered pings. **4 consecutive unanswered (2 s) or transport close =
connection dead.** Client: stop all modules, show the connection error.
Server: stop streams and generator, release this connection's locks and
files, park hardware (QA40x attenuator safe). Reconnection is a **new
session** - there are no resume semantics; locks are re-acquired explicitly.

### 4.2 Errors

`PROTO_MISMATCH`, `BAD_REQUEST`, `UNSUPPORTED`, `NOT_LOCKED`,
`DEVICE_LOCKED` (extra field `by`), `DEVICE_STALE`, `DEVICE_ERROR`,
`BACKEND_MISMATCH`, `NO_SUCH_FILE`, `FILE_TOO_LARGE`, `INTERNAL`.

A `DEVICE_ERROR` from `capture.open`/`gen.open` MAY carry the optional extra
field `reason` - the name of a device-failure reason
(`DEVICE_DISCONNECTED`, `DEVICE_NOT_ANSWERING`, `DEVICE_IN_USE`,
`DEVICE_NOT_FOUND`, `FORMAT_UNSUPPORTED`, `UNKNOWN`) classified by the server
backend that owns the driver, so the client can tell its operator WHY in the
operator's language - `message` is the server's language and its driver codes
are nobody's. Absent, or a name the client does not know, reads as `UNKNOWN`
(§1); `UNKNOWN` itself is left off the wire.

### 4.3 Devices and locks

Devices are identified by `{backend, index, input, name}` - `backend` is the
server-side `AudioBackendType` name (`"JAVASOUND"`, `"QA40X"`, ...), `index` the
backend's device index, `input` the direction, `name` the device name. The
server validates `name` on every call that TAKES, OPENS or CALIBRATES a device
(`device.acquire`, `capture.open`, `gen.open`, `device.setCalibration`,
`device.setCard` - v1.1: a calibration or card binding accepted for whatever
now sits at a moved index would mis-scale
every later measurement on it) and answers `DEVICE_STALE` when
the index moved (hot-plug re-enumeration). `device.release` is deliberately
exempt: the lock is keyed on the index the client sent, so releasing it always
does the right thing, and refusing a release after a hot-plug would strand the
device with a client that has no way left to let go of it.

| Type | Fields | Response data / notes |
|---|---|---|
| `backend.list` | - | `{backends:[{backend, displayName, available, operational, hasBitDepth}]}` - the server's backends WITHOUT device detail (fast; feeds the client's combo entries). `operational` distinguishes present-but-nonfunctional platforms (e.g. CoreAudio on Windows). |
| `backend.select` | `backend` | **Session-scoped** backend selection (each connection has its own; other sessions are unaffected). Response data: the selected backend's full entry in `devices.list` shape (devices + formats inlined) - one round-trip fills the device combos. After selection, device refs in `capture.open`/`gen.open` MUST name the selected backend, else error `BACKEND_MISMATCH`. Re-selecting switches; locks already held stay valid (locks are device-scoped, not selection-scoped). |
| `devices.list` | - | `{backends:[{backend, devices:[{index,name,description,vendor,input,output, formats:[{rate,bits,channels}], hasBitDepth, lock:null\|{by}, cal:null\|{fsRmsLeft,fsRmsRight}, card:null\|"<card name>", calFromDevice:bool}]}]}` - ALL backends, formats inlined; the lock-overview view (graying). **One object per device AND DIRECTION**: a backend's index space is per-direction, so a duplex interface appears twice, once with `input:true` and its input index and once with `input:false` and its output index. `output` is therefore the exact negation of `input` - the pair names WHICH list the `index` belongs to, not the hardware's duplex capability - and a client copies `{backend,index,input,name}` straight into a device ref. **`cal` is the server-stored calibration for this device+direction** - the full-scale RMS volts per channel of the device card row in force on the SERVER, whose devices.yaml owns the calibration of every device plugged into it (v1.1: calibration lives where the device is connected, so every client arrives already calibrated). `cal: null` when the server has no card for it; the device is then **uncalibrated** on every client - since 1.2 the client's legacy default full scales are a runtime-only fallback (never persisted, never sent), so clients are expected to warn the user and may offer to hand a matching local card up to the server - the WHOLE card via `cards.put` + `device.setCard` (v1.1: the range table and the channel mode are what make the values mean anything), falling back to the values alone via `device.setCalibration` when the bench already has a card of that name. A client never calibrates a server's device from its own store: a local card is the source of an offer, never a silent substitute. **`card` (v1.1) is the logical name of the server card IN FORCE for this device** - the user's saved binding (`device.setCard`) when one exists, else the server's name-match resolution; `null` only when NO card correlates at all.  It names the card the `cal` values actually come from, so a chooser can show the truth instead of an empty combo for a device the server recognises without an explicit binding. The binding is keyed on the device NAME: a card describes a physical box, so a backend that lists one name in both directions (the QA40x) carries the SAME `card` in both entries - one box, one card; where a backend names its directions apart (the usual sound-card case) bindings are per-direction by construction. A bound card that lacks usable rows for one direction leaves that direction uncalibrated (`cal: null`) - the honest state, never a silent fallback to another card. **`calFromDevice` (v1.1)** says the full scales of this device AND direction are the DEVICE's own - a QA40x reads them from its EEPROM - so a client shows them read-only instead of offering an edit `device.setCalibration` would answer `BAD_REQUEST` after the operator had already typed a value. Plain `false` (not an omission) when no card correlates or the card is an ordinary one: "editable" must be what a server SAYS, never what an absent field is read as. |
| `device.acquire` | device ref | Grants this connection an **exclusive lock** on that device+direction, or `DEVICE_LOCKED{by}`. All streaming/generator calls require the lock. |
| `device.release` | device ref | Also closes any open stream/generator on it |
| `device.setCalibration` | device ref + `fsRmsLeft`, `fsRmsRight` | Writes the calibration of a SERVER-owned device (v1.1): the server stores the two full-scale RMS volts into its device card for that device+direction (creating the card if none exists), persists, and broadcasts `ev.devices.changed` - every client's `cal` view refreshes. **Requires the lock on the device+direction the ref names** (it changes what every measurement on that device means) - NOT the QA40x rule of "either direction", which belongs to writes that name no device (§4.6): a DAC calibration sent under an input lock is `NOT_LOCKED`, and must be, or it would land on a device its sender never took. Not answered for a `calibrationFromDevice` card (the QA40x): its values come from the analyzer itself and are not a client's to write - `BAD_REQUEST`. The card it creates when none exists is a bare one - one `default` range row - which is also the "empty card" fallback used when the operator declines to author one. |
| `device.setCard` | device ref + `card` (string \| `null`) | Binds the server's device - keyed by NAME, see the `card` field note on granularity - to one of the server's device cards by logical name (v1.1): the user's card choice, persisted where the device lives, so a QA402-vs-QA403 pick survives restarts and reaches every client. `null` unbinds (back to name-match resolution). The named card must exist on the server - `BAD_REQUEST` otherwise (a typo must not silently uncalibrate a device). `calibrationFromDevice` cards ARE bindable: binding **chooses** a card, it writes no values into it (contrast `device.setCalibration`). **Requires the device lock** (the ref's device+direction is the write ticket; the binding covers the whole box); persists and broadcasts `ev.devices.changed` (the binding changes which `cal` is in force). Response data: none. |
| `device.setActiveRange` | device ref + `range` (row label) + `channel` (`"both"` \| `"left"` \| `"right"`, default `"both"`) | Moves the ACTIVE range of the card in force for that device+direction (v1.1) - which row is active IS the full scale in force, so this is a calibration change by another name: same lock rule as `device.setCalibration` (the ref's own device+direction), persists, and broadcasts `ev.devices.changed` because every client's `cal` for that device has just changed. `BAD_REQUEST` when no card is in force for the device or it has no row of that label - a marker pointing at nothing would silently fall back to the first row and mis-scale everything measured afterwards. `channel` is `both` for the usual card whose two channels share one marker; `left`/`right` move an INDEPENDENT card's own per-channel markers. **The QA40x is not re-ranged through here**: its attenuator is the DEVICE's state and §4.6's `qa40x.setInputRange`/`setOutputRange` own it - sending both would move it twice. |
| `cards.list` | - (read-only, no lock) | `{cards:[{name, input:bool, output:bool, content:{...}}]}` (v1.1) - the server's device cards by logical name; the booleans say whether the card holds at least one row for that direction (the same filter a client applies to its own local cards), and `content` is the whole card in the shape `cards.put` accepts, so a client can show the range table behind a choice and name a row for `device.setActiveRange`. **The booleans and `cal` are deliberately NOT the same predicate**: a direction is offered when it has a row at all, while `cal` additionally requires both full scales to be above zero - so a card may honestly be offered for a direction and still leave it `cal: null` after binding (the spec's "a bound card that lacks usable rows for one direction leaves that direction uncalibrated" case, an uncalibrated row being exactly such a row). A chooser that hid every uncalibrated card would hide the very cards an operator opens the chooser to calibrate. Feeds the client's card-binding chooser for `device.setCard`. |
| `cards.put` | `content` - the whole card: `{name, match:[...], input:{channels, ranges:[{label, fsVrms:{left,right}, calibrated, displayLabel?}], activeRange}, output:{...}}` - `displayLabel` is the optional device-authored display text of a row (the QA40x verbose range labels), carried verbatim both ways so a bench's card shows on every client exactly as the analyzer names its ranges | Stores a NEW card on the server (v1.1), in the same vocabulary the server's own `devices.yaml` uses - the propagation half of "calibration lives where the device is connected": a client whose local card describes a box the bench has no card for hands the WHOLE card up (range table, channel mode and match list included), instead of a bare pair of numbers that would land on a one-row card describing nothing. **Create only** - a name the server already has is `BAD_REQUEST`: a client cannot see what that card holds before it writes, so a silent overwrite would discard another operator's calibration of the same box with nothing on screen to say so. `BAD_REQUEST` also for a nameless card, for a `calibrationFromDevice` card (an analyzer's own values are read where the analyzer is plugged in, never invented by a client) and for a card with no range row in either direction. **No lock and no broadcast**: a card nothing is bound to is in force nowhere, so it changes no measurement and no client's `cal` - the `device.setCard` that follows is the write that does both. |
| `ev.devices.changed` | s->c broadcast | Full `devices.list` payload; sent on hot-plug, on any lock change, and (v1.1) on an accepted `device.setCalibration`/`device.setCard`/`device.setActiveRange`/`qa40x.setInputRange`/`qa40x.setOutputRange` - a calibration write, a card binding or an active-range move changes the payload's `cal`/`card`, and the range in force IS the QA40x's full scale. (`cards.put` does NOT broadcast: it creates a card nothing is bound to, so no device's `cal` moves.) Clients gray out locked devices live. **A client must ACT on a changed `cal`, not merely cache it** (v1.1): when the payload's calibration for the device it is currently measuring on differs from the one it holds, it re-applies it there and then - another operator recalibrating that device, or moving its range, changes what every reading means, and a client that waited for its next device open would go on showing volts computed against a full scale the bench no longer holds. |
| `ev.device.error` | s->c | `{direction:"input"\|"output", detail, reason}` - server-side capture/playback failure or QA40x detach. The client surfaces it exactly like a local device error (message + stop the affected modules). `reason` is the §4.2 device-failure reason name, always present on this event (it is always a device's failure, `UNKNOWN` included) and read as `UNKNOWN` when absent or unknown to the client. |

Locks are per `(backend, index, direction)`, owned by one WS connection, and
auto-released on connection death (§4.1). A multi-client server: every client
sees all devices; each device streams to at most one.

**Backend listing and selection** (an explicit API, by design): a
client presents each server backend as its own selectable entry
("<server name> -> <backend>") from `backend.list`, and commits a choice with
`backend.select`. Selection is **per-session**: the server serves ALL of its
backends concurrently across sessions and has no global "active backend" -
two clients using different backends of the same server is a supported case.
Backend semantics (e.g. the QA40x equal-rates rule, bit-depth support) are
known to the client per backend type and enforced client-side exactly as for
local backends; the server still re-validates.

### 4.4 Capture streaming

| Type | Fields | Response data |
|---|---|---|
| `capture.open` | device ref + `rate`, `bits` | `{captureId, rate, bits, channels:2, frameBytes}` - requires input lock; the granted `rate` may differ (device reality), client re-pins. Payload is the **native PCM byte stream** of the server-side capture (signed little-endian interleaved stereo at `bits`), so the client decodes with the same code path a local device uses. |
| `capture.start` | `captureId` | Binary PCM frames (§5) begin |
| `capture.stop` | `captureId` | Stream pauses; counters keep their values |
| `capture.close` | `captureId` | - |

One open capture per device. QA40x equal-rates rule is enforced server-side:
`capture.open`/`gen.open` on QA40x with mismatched rates -> `BAD_REQUEST`.

### 4.5 Remote generator

The generator runs **on the server** (DDS next to the DAC, no uplink audio).
The command vocabulary mirrors the desktop `GeneratorController` setter
surface. All `gen.*` require the output-device lock.

| Type | Fields | Notes |
|---|---|---|
| `gen.open` | device ref + `rate`, `bits`, `ditherBits`, `outputChannels` | -> `{genId, rate}`. Opens server-side playback; creates the generator (silent until `gen.start`). |
| `gen.config` | `genId` + any of: `form`, `frequency`, `amplitudeVrms`, `dacFsVoltageAmpl`, `rightLaneScale`, `rectangleDuty`, `triangleDuty`, `dual:{frequency2, amp1Pct, amp2Pct}`, `sweep:{f0, f1, durationSamples, leadInSamples, fadeInSamples, fadeOutSamples, loop}`, `compensation:{ampRatios[], hNums[], phiInits[]}`, `dualCompensation:{ampRatios[], aCoef[], bCoef[], phiInits[]}`, `clearCompensation:true`, `fileLoop` | Partial update - only present fields are applied. Numeric values are final (the client applies calibration/full-scale math exactly as it does locally). `rightLaneScale` is `fsLeft/fsRight` for a card whose two DAC full-scales differ (`dacFsVoltageAmpl` is the LEFT one, which the mono amplitude is computed against); it goes to the playback lane's quantizer, not to the DDS. **Defaults at `gen.open` (v1.1):** `dacFsVoltageAmpl` and `rightLaneScale` initialise from the SERVER's stored calibration for the opened output device (its device card - calibration lives where the device is connected), falling back to `1.0` only for a device the server has no card for. A client push still overrides; a client that never sends them now runs at the bench's true full scale instead of a placeholder. **`fileLoop` (v1.1):** the live repeat flag of the file currently playing (§3, `gen.playFile`). The server re-reads it at each end of stream, so setting it true mid-play makes the lap that is ending repeat, and setting it false lets that lap **finish** rather than cutting playback off - matching what a local player does with the same toggle. Applied only when a file session exists on that generator; ignored like any other inapplicable field otherwise. |
| `gen.start` / `gen.stop` | `genId` | Start/stop emission. Starting a sweep resets the sweep position and injects a `sweepStart` marker (§5) into this connection's open capture streams. |
| `gen.fftGrid` | `genId`, `fftSize`, `snapEnabled` | Server snaps the emitted frequency to the FFT bin grid `k·rate/fftSize` when enabled - same math as the client-side snap, so both compute identical values. |
| `gen.trim` / `gen.trim2` | `genId`, `hz` | FLL feedback - absolute corrected frequency for tone 1 / tone 2 (the servo lives client-side in the FFT, the actuator here). |
| `gen.trimReset` | `genId` | Back to nominal |
| `gen.state` | `genId` | Pull the full state (same payload as `ev.gen.state`) |
| `gen.playFile` | `genId`, `fileId`, `loop` | Plays an uploaded file (§3) through the generator lane. `loop` is the flag the file STARTS with; it is not immutable - `gen.config`'s `fileLoop` changes it while the file plays (v1.1). A file that reaches its end without looping stops the lane and hands it back to the tone's format. The `ev.gen.state` carrying `file:{finished:true}` is pushed **before** that handback, and a further state follows it describing a generator with no file (`file:{playing:false, finished:false, rate:0, bits:0}`) - so a client must react to the finished push when it arrives and must not wait for the *last* state before a file, which never carries it. |
| `gen.stopFile` | `genId` | - |
| `gen.close` | `genId` | Stops, closes server-side playback |
| `ev.gen.state` | s->c | Pushed on **every state change**, plus ≤ 10 Hz while a sweep or file is running: `{genId, running, form, nominalHz, emitHz, emit2Hz, amplitudeVrms, sweep:{active, posSamples, durationSamples, loop}, file:{playing, posSamples, finished, rate, bits}}`. `emitHz`/`emit2Hz` are the post-snap, post-trim frequencies actually emitted - clients feed their FFT/scope hint plumbing from these, never from the nominal values. **`0.0` means "this form emits no such tone"** and is never a frequency: a sweep and the noise forms report `emitHz` 0, and every single-tone form reports `emit2Hz` 0. A client MUST NOT draw a hint marker for a 0 - the field says there is nothing to point at, not that the generator is at DC. |

### 4.6 QA40x extension (`caps` contains `"qa40x"`)

Mirrors the local `Qa40xDeviceManager` surface; requires the QA40x lock
(either direction) unless marked read-only.

| Type | Fields | Response data |
|---|---|---|
| `qa40x.info` | - (read-only) | `{firmwareVersion, usbVoltage, usbCurrent, isoCurrent, temperature, capability, capability2, serialNumber}` - formatted strings as produced by the server |
| `qa40x.ranges` | - (read-only) | `{inputDbv:[...], outputDbv:[...], activeInputDbv, activeOutputDbv}` |
| `qa40x.setInputRange` / `qa40x.setOutputRange` | `dbv` | - |
| `qa40x.calibration` | - (read-only) | `{adc:[{dbv,left,right}...], dac:[{dbv,left,right}...]}` - linear factors, so the client's `calibrationFromDevice` card pipeline works unchanged |
| `qa40x.settings` | optional `{i2sEnabled}` | Get (no field, **read-only**) or set (lock required); affects supported bit depths exactly as locally |

## 5. Binary frames (audio data)

WebSocket **binary** messages on the same socket. Fixed 16-byte header, all
fields little-endian:

| Offset | Size | Field |
|---|---|---|
| 0 | u8  | `frameType`: 1 = PCM, 2 = MARKER, 3 = GAP, 4 = *reserved: uplink PCM (NetPcmPlayback - NOT implemented in v1)* |
| 1 | u8  | reserved, 0 |
| 2 | u16 | `streamId` (= `captureId`) |
| 4 | u64 | `packetCounter` - per stream, starts at 0, +1 per frame **of any type** |
| 12| u32 | `n` - see per-type meaning |
| 16| ...   | payload |

- **PCM (1):** `n` = payload byte count; payload = native interleaved stereo
  PCM as declared by `capture.open`. Sent per capture batch (~10-100 ms).
- **MARKER (2):** `n` = marker kind: 1 = `sweepStart`. No payload. In-band
  position: the first PCM byte *after* this frame is aligned with sweep
  output sample 0 to within the server's capture-batch granularity. Exact
  alignment stays the analyzer's job (it already owns lead-in/sync handling
  for the local path); the marker bounds the search.
- **GAP (3):** `n` = lost stereo frames. Sent when the server itself dropped
  capture data (consumer stalled, device overrun). An explicit confession -
  the client resets averaging instead of silently splicing. GAP frames also
  increment `packetCounter`.

**Net discontinuity detection:** a `packetCounter` jump means transport-layer
loss or reordering - impossible over intact TCP, so the client treats it as a
protocol error: surface + stop, like a device error. GAP frames are the
honest, expected path. The existing time/spectral discontinuity detectors stay
armed regardless - they cover everything between the server's RAM and the ADC.

## 6. Lifecycle walkthroughs

**Scope/FFT session:** `hello` -> `devices.list` -> `device.acquire(in)` ->
`capture.open` -> `capture.start` -> PCM frames -> ... -> `capture.stop` ->
`device.release` -> `bye`.

**Generator + FFT with FLL:** acquire out + in, `gen.open`, `gen.config{form,
frequency, amplitudeVrms, dacFsVoltageAmpl}`, `gen.fftGrid{N, snap}`,
`gen.start`, `capture....` - FFT measures, sends `gen.trim{hz}`; `ev.gen.state`
confirms `emitHz`; hints feed from the confirmed state.

**FreqResp sweep:** acquire out + in, `capture.open/start`, `gen.open`,
`gen.config{sweep..., form:LOG_SWEEP}`, `gen.start` -> MARKER `sweepStart`
arrives in the capture stream -> client collects `leadIn + duration` samples ->
`gen.stop` -> analysis runs client-side on the assembled record.

**Client vanishes mid-capture:** server misses 4 pings -> stops capture +
generator, parks QA40x, releases locks, broadcasts `ev.devices.changed` -
other clients see the devices free ≤ 2.5 s later.

## 7. Explicitly out of scope in v1

Client->server audio streaming (frame type 4 reserved; the Java
`NetPcmPlayback` skeleton throws `UnsupportedOperationException`), TLS/auth,
compression, clock-drift correction (one clock - the hardware's), resume
semantics, server-side analysis of any kind (the server is hardware control +
generator + dumb pipe; all measurement math stays in the client).
