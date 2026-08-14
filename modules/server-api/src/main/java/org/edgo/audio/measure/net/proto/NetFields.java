/*
 * Phonalyser - precision audio measurement workbench.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.edgo.audio.measure.net.proto;

import org.edgo.audio.measure.enums.DeviceFailureReason;

import lombok.experimental.UtilityClass;

/**
 * Every JSON field name the net protocol puts on the wire (spec 3 and 4.1 -
 * 4.6), quoted once.  {@link NetProto} owns the numbers, {@link MessageType}
 * the {@code t} discriminators, {@link ErrorCode} the error codes - this owns
 * the names.
 *
 * <p>Why a constants holder rather than a record per message: {@code gen.config}
 * is a PARTIAL update (spec 4.5) where absence is meaningful, so its payload has
 * no fixed shape to bind, and {@link NetMessage} deliberately keeps the parsed
 * object instead of typed fields.  That leaves the field names themselves as the
 * only contract - and a hand-typed {@code "emmitHz"} is a silent runtime no-op,
 * not a compile error.  Every producer and consumer names its fields from here.
 *
 * <p>A name shared by several messages is declared ONCE, with the uses listed:
 * {@code rate} is the same field in a device format, in {@code capture.open} and
 * in {@code gen.open}, and {@code input}/{@code output} serve both as device
 * direction flags and as the {@code direction} values of
 * {@code ev.device.error}.  The response envelope's own eight keys stay private
 * to {@link NetMessage} (nothing outside it types them), and the discovery
 * datagram binds through the {@link Beacon} record.
 */
@UtilityClass
public class NetFields {

    /* ------------------------- shared across messages ------------------------- */

    /** Display name: server (2.1, 4.1), client (4.1) or device (4.3). */
    public static final String NAME = "name";
    /** Protocol version - highest offered, or the chosen one (1, 2.1, 3, 4.1). */
    public static final String PROTO = "proto";
    /** Application version string, e.g. {@code "1.2.0"} (2.1, 2.2, 3, 4.1). */
    public static final String APP = "app";
    /** Per-installation server UUID; remembered lists key on it (2.1, 2.2, 3). */
    public static final String SERVER_ID = "serverId";
    /** The one port a server serves both planes on - the HTTP endpoints of
     *  spec 3 and the WebSocket upgrade of spec 4 (2.1, 2.2, 3). */
    public static final String PORT = "port";
    /** Sample rate in Hz - device format, capture and generator (4.3 - 4.5). */
    public static final String RATE = "rate";
    /** Sample depth in bits - device format, capture and generator (4.3 - 4.5). */
    public static final String BITS = "bits";
    /** Channel count of a device format or a capture stream (4.3, 4.4). */
    public static final String CHANNELS = "channels";
    /** Repeat flag - sweep (4.5 {@code sweep}) and file playback (4.5). */
    public static final String LOOP = "loop";
    /** Live repeat flag of the RUNNING file (4.5 {@code gen.config}, v1.1) -
     *  the toggle a {@code gen.playFile} could otherwise only carry once. */
    public static final String FILE_LOOP = "fileLoop";
    /** Uploaded-file handle: {@code PUT /files} answers it, {@code gen.playFile}
     *  spends it (3, 4.5). */
    public static final String FILE_ID = "fileId";

    /* ------------------------------ session - 4.1 ------------------------------ */

    /** Lowest protocol version the client speaks; absent = same as {@code proto}
     *  (spec 1 negotiates the RANGE, see {@link NetProto#PROTO_MIN_VERSION}). */
    public static final String PROTO_MIN = "protoMin";
    /** Client application and version, for the server log and lock info. */
    public static final String CLIENT = "client";
    /**
     * The session's own handle, answered by {@code hello} and spent by
     * {@code capture.attach} (spec 4.1, 4.7): a cryptographically random UUID
     * the server generates per session.
     *
     * <p>A SECRET.  It is the ticket that binds a fresh socket to the session
     * that opened the capture, so it must never be logged, shown in a UI, or
     * carried in a beacon, a peer-table row or an error message.  What a client
     * is KNOWN by is {@link #NAME}, which is what a {@code DEVICE_LOCKED}
     * refusal quotes.
     */
    public static final String CLIENT_ID = "clientId";
    /** Optional capability tokens the server advertises in the hello response. */
    public static final String CAPS = "caps";
    /** {@link #CAPS} token: the QA40x extension of 4.6 is served. */
    public static final String CAP_QA40X = "qa40x";
    /** {@link #CAPS} token: the remote generator of 4.5 is served. */
    public static final String CAP_GEN = "gen";
    /** {@link #CAPS} token: file upload and remote playback are served. */
    public static final String CAP_FILES = "files";

    /* ---------------------------- HTTP - 2.2 and 3 ---------------------------- */

    /** {@code GET /servers}: the peer-table array (2.2). */
    public static final String SERVERS = "servers";
    /** Peer-table entry: the address the datagram came FROM, never self-reported. */
    public static final String HOST = "host";
    /** Peer-table entry: true for the answering server's own row. */
    public static final String SELF = "self";
    /** {@code GET /info}: the server's operating system. */
    public static final String OS = "os";
    /** {@code GET /info}: server uptime in seconds. */
    public static final String UPTIME_S = "uptimeS";
    /** {@code PUT /files}: the accepted byte count. */
    public static final String BYTES = "bytes";
    /** {@code GET /health}: the liveness flag.  Same word as the response
     *  envelope's {@code ok}, but a different field on a different plane - the
     *  envelope's keys stay private to {@link NetMessage}, and nothing outside
     *  the HTTP front types this one. */
    public static final String OK = "ok";
    /** A refused HTTP answer's failure object (3), carrying the same
     *  {@code {code,message}} pair a failed control-plane response does - so a
     *  413 is readable as {@link ErrorCode#FILE_TOO_LARGE} rather than as an
     *  anonymous refusal.  The WS envelope keeps its own private copy of these
     *  three keys: nothing outside {@link NetMessage} types the envelope. */
    public static final String ERROR = "error";
    /** {@link #ERROR}: the {@link ErrorCode} name (4.2). */
    public static final String CODE = "code";
    /** {@link #ERROR}: the human-readable detail. */
    public static final String MESSAGE = "message";

    /* -------------------------- devices and locks - 4.3 -------------------------- */

    /** {@code devices.list}: the per-backend array. */
    public static final String BACKENDS = "backends";
    /** Device ref: the server-side {@code AudioBackendType} name. */
    public static final String BACKEND = "backend";
    /** {@code backend.list}: the backend's operator-visible name, as the server
     *  spells it - so a client shows "QA40x", not the enum constant. */
    public static final String DISPLAY_NAME = "displayName";
    /** {@code backend.list}: a module supplying this backend is in the build. */
    public static final String AVAILABLE = "available";
    /** {@code backend.list}: the backend can actually run on this host, which is
     *  the other half of {@link #AVAILABLE} - a build ships every backend
     *  module regardless of platform, so CoreAudio is available on Windows and
     *  not operational there. */
    public static final String OPERATIONAL = "operational";
    /** A backend's device array inside {@link #BACKENDS}. */
    public static final String DEVICES = "devices";
    /** Device ref: the backend's device index, validated against {@link #NAME}. */
    public static final String INDEX = "index";
    /** Device description text. */
    public static final String DESCRIPTION = "description";
    /** Device vendor text. */
    public static final String VENDOR = "vendor";
    /** Device capture direction - also the {@link #DIRECTION} value for an input
     *  failure in {@code ev.device.error}. */
    public static final String INPUT = "input";
    /** Device playback direction - also the {@link #DIRECTION} value for an
     *  output failure in {@code ev.device.error}. */
    public static final String OUTPUT = "output";
    /** The inlined {@code {rate,bits,channels}} array of a device. */
    public static final String FORMATS = "formats";
    /** Whether the device lets the client choose a bit depth. */
    public static final String HAS_BIT_DEPTH = "hasBitDepth";
    /** Lock state: JSON null when free, else an object carrying {@link #BY}. */
    public static final String LOCK = "lock";
    /** The calibration the SERVER stores for this device and direction: JSON null
     *  when it has no card for it, else an object carrying {@link #FS_RMS_LEFT}
     *  and {@link #FS_RMS_RIGHT} (spec 4.3, v1.1 - calibration lives where the
     *  device is connected, so every client arrives already calibrated). */
    public static final String CAL = "cal";
    /** {@link #CAL} and {@code device.setCalibration}: the LEFT channel's
     *  full-scale in volts RMS.  RMS on the wire in both directions - a card
     *  stores RMS, and a generator's peak-amplitude form is {@code × √2} the
     *  reader applies. */
    public static final String FS_RMS_LEFT = "fsRmsLeft";
    /** {@link #CAL} and {@code device.setCalibration}: the RIGHT channel's
     *  full-scale in volts RMS. */
    public static final String FS_RMS_RIGHT = "fsRmsRight";
    /** Whether the full scales of this device and direction are the DEVICE's own -
     *  a QA40x reads them from its EEPROM (spec 4.3, v1.1).  It is what makes a
     *  calibration write refusable before it is sent: a client shows such a device
     *  read-only instead of offering an edit its own bench would answer
     *  {@code BAD_REQUEST}. */
    public static final String CAL_FROM_DEVICE = "calFromDevice";
    /** The logical name of the server card BOUND to a device and direction -
     *  the user's saved card choice, authoritative over the server's name-match
     *  resolution; JSON null when nothing is bound (spec 4.3, v1.1).  Also the
     *  request field of {@code device.setCard}, where null unbinds. */
    public static final String CARD = "card";
    /** {@code cards.list}: the server's device cards, each an object carrying
     *  {@link #NAME} plus {@link #INPUT} / {@link #OUTPUT} - whether the card
     *  holds at least one usable row for that direction (spec 4.3, v1.1) - and
     *  {@link #CONTENT}, the card itself. */
    public static final String CARDS = "cards";
    /** The card CONTENT: the whole card in the same vocabulary its
     *  {@code devices.yaml} entry uses - {@code {name, match:[...],
     *  input:{channels, calibrationFromDevice, ranges:[{label, fsVrms:{left,
     *  right}, calibrated}], activeRange}, output:{...}}}.  The request body of
     *  {@code cards.put} and the per-card detail of a {@code cards.list} entry
     *  (spec 4.3, v1.1): ONE shape, read and written by one codec on both sides,
     *  so a card that travelled up can be shown again exactly as it was stored. */
    public static final String CONTENT = "content";
    /** {@code device.setActiveRange}: the label of the range row that becomes the
     *  active one on the device's card (spec 4.3, v1.1). */
    public static final String RANGE = "range";
    /** {@code device.setActiveRange}: which channel the new active range applies
     *  to - {@link #BOTH} (the LINKED / MONO case), {@code "left"} or
     *  {@code "right"} (an INDEPENDENT card, whose two channels sit on their own
     *  rows). */
    public static final String CHANNEL = "channel";
    /** {@link #CHANNEL}: both channels at once, the only value a LINKED or MONO
     *  card accepts and the default when the field is absent.  The other two
     *  values are {@link #LEFT} and {@link #RIGHT}, the same two words a
     *  calibration row names its channels with. */
    public static final String BOTH = "both";
    /** Human-readable owner - of a {@link #LOCK}, and of a
     *  {@code DEVICE_LOCKED} error. */
    public static final String BY = "by";
    /** {@code ev.device.error}: {@link #INPUT} or {@link #OUTPUT}. */
    public static final String DIRECTION = "direction";
    /** {@code ev.device.error}: the failure text shown to the operator. */
    public static final String DETAIL = "detail";
    /** {@code ev.device.error}, and the {@code error} object of a refused
     *  {@code capture.open} / {@code gen.open}: the name of a
     *  {@link DeviceFailureReason} - WHY the device failed, in words the far
     *  side can translate, since {@link #DETAIL} is the server's language and
     *  a driver code is nobody's.  OPTIONAL, in both directions: a peer that
     *  does not send it, or sends a value this build never heard of, is read as
     *  {@link DeviceFailureReason#UNKNOWN} (spec 1). */
    public static final String REASON = "reason";

    /* --------------------------- capture streaming - 4.4 --------------------------- */

    /** Capture handle; also the binary frames' {@code streamId} (4.4, 5). */
    public static final String CAPTURE_ID = "captureId";
    /** Bytes per stereo frame of the native PCM stream. */
    public static final String FRAME_BYTES = "frameBytes";

    /* ---------------------------- remote generator - 4.5 ---------------------------- */

    /** Generator handle returned by {@code gen.open}. */
    public static final String GEN_ID = "genId";
    /** {@code gen.open} and {@code gen.config}: TPDF dither depth in bits.
     *  The open carries the initial depth; the config push is the live half,
     *  applied to the tone that is playing. */
    public static final String DITHER_BITS = "ditherBits";
    /** {@code gen.open}: which physical DAC lane carries the signal, as the
     *  server's {@code OutputChannels} name - {@code "BOTH"}, {@code "LEFT"} or
     *  {@code "RIGHT"}.  Not a channel COUNT: the lane is stereo either way, and
     *  what a generator lets the operator choose is which side of it is driven
     *  and which gets digital silence. */
    public static final String OUTPUT_CHANNELS = "outputChannels";
    /** Waveform name. */
    public static final String FORM = "form";
    /** Nominal tone frequency in Hz, before snap and trim. */
    public static final String FREQUENCY = "frequency";
    /** Amplitude in volts RMS - final, the client already did the calibration. */
    public static final String AMPLITUDE_VRMS = "amplitudeVrms";
    /** DAC full-scale voltage amplitude the client computed. */
    public static final String DAC_FS_VOLTAGE_AMPL = "dacFsVoltageAmpl";
    /** {@code gen.config}: the RIGHT lane's output scale, {@code fsLeft/fsRight}
     *  - the client's own calibration arithmetic, final like every other number
     *  here (spec 4.5).  {@code 1.0} for a card whose two DAC full-scales are
     *  equal, which is why an older server ignoring the field behaves exactly as
     *  it did. */
    public static final String RIGHT_LANE_SCALE = "rightLaneScale";
    /** Rectangle duty cycle. */
    public static final String RECTANGLE_DUTY = "rectangleDuty";
    /** Triangle duty cycle. */
    public static final String TRIANGLE_DUTY = "triangleDuty";
    /** Dual-tone sub-object. */
    public static final String DUAL = "dual";
    /** {@link #DUAL}: the second tone's frequency in Hz. */
    public static final String FREQUENCY2 = "frequency2";
    /** {@link #DUAL}: first tone's share in percent. */
    public static final String AMP1_PCT = "amp1Pct";
    /** {@link #DUAL}: second tone's share in percent. */
    public static final String AMP2_PCT = "amp2Pct";
    /** Sweep sub-object - in {@code gen.config} and in the state event. */
    public static final String SWEEP = "sweep";
    /** {@link #SWEEP}: start frequency in Hz. */
    public static final String F0 = "f0";
    /** {@link #SWEEP}: end frequency in Hz. */
    public static final String F1 = "f1";
    /** Sweep length in samples - configured, and echoed in the state event. */
    public static final String DURATION_SAMPLES = "durationSamples";
    /** {@link #SWEEP}: samples emitted before the sweep proper starts. */
    public static final String LEAD_IN_SAMPLES = "leadInSamples";
    /** {@link #SWEEP}: fade-in length in samples. */
    public static final String FADE_IN_SAMPLES = "fadeInSamples";
    /** {@link #SWEEP}: fade-out length in samples. */
    public static final String FADE_OUT_SAMPLES = "fadeOutSamples";
    /** Harmonic-compensation sub-object. */
    public static final String COMPENSATION = "compensation";
    /** Compensation: per-harmonic amplitude ratios. */
    public static final String AMP_RATIOS = "ampRatios";
    /** {@link #COMPENSATION}: harmonic numbers. */
    public static final String H_NUMS = "hNums";
    /** Compensation: per-component initial phases in radians. */
    public static final String PHI_INITS = "phiInits";
    /** Dual-tone compensation sub-object. */
    public static final String DUAL_COMPENSATION = "dualCompensation";
    /** {@link #DUAL_COMPENSATION}: the a-coefficients. */
    public static final String A_COEF = "aCoef";
    /** {@link #DUAL_COMPENSATION}: the b-coefficients. */
    public static final String B_COEF = "bCoef";
    /** Drops every compensation table when true. */
    public static final String CLEAR_COMPENSATION = "clearCompensation";
    /** {@code gen.fftGrid}: the analyzer's FFT length. */
    public static final String FFT_SIZE = "fftSize";
    /** {@code gen.fftGrid}: whether to snap onto {@code k·rate/fftSize}. */
    public static final String SNAP_ENABLED = "snapEnabled";
    /** {@code gen.trim}/{@code gen.trim2}: the absolute corrected frequency. */
    public static final String HZ = "hz";
    /** {@code ev.gen.state}: emission is on. */
    public static final String RUNNING = "running";
    /** {@code ev.gen.state}: the configured frequency, before snap and trim. */
    public static final String NOMINAL_HZ = "nominalHz";
    /** {@code ev.gen.state}: the frequency actually emitted - post-snap,
     *  post-trim.  Hints and analyzers read THIS, never {@link #NOMINAL_HZ}. */
    public static final String EMIT_HZ = "emitHz";
    /** {@code ev.gen.state}: the second tone's emitted frequency. */
    public static final String EMIT2_HZ = "emit2Hz";
    /** {@code ev.gen.state} {@link #SWEEP}: a sweep is in progress. */
    public static final String ACTIVE = "active";
    /** {@code ev.gen.state}: position within the sweep or the file, in samples. */
    public static final String POS_SAMPLES = "posSamples";
    /** {@code ev.gen.state}: the file-playback sub-object. */
    public static final String FILE = "file";
    /** {@code ev.gen.state} {@link #FILE}: playback is running. */
    public static final String PLAYING = "playing";
    /** {@code ev.gen.state} {@link #FILE}: playback reached the end. */
    public static final String FINISHED = "finished";

    /* --------------------------- QA40x extension - 4.6 --------------------------- */

    /** {@code qa40x.info}: firmware build. */
    public static final String FIRMWARE_VERSION = "firmwareVersion";
    /** {@code qa40x.info}: USB bus voltage. */
    public static final String USB_VOLTAGE = "usbVoltage";
    /** {@code qa40x.info}: USB bus current. */
    public static final String USB_CURRENT = "usbCurrent";
    /** {@code qa40x.info}: ISO-supply current (QA402 only). */
    public static final String ISO_CURRENT = "isoCurrent";
    /** {@code qa40x.info}: board temperature. */
    public static final String TEMPERATURE = "temperature";
    /** {@code qa40x.info}: the feature-bit word. */
    public static final String CAPABILITY = "capability";
    /** {@code qa40x.info}: the per-model capability word. */
    public static final String CAPABILITY2 = "capability2";
    /** {@code qa40x.info}: the unit's serial number. */
    public static final String SERIAL_NUMBER = "serialNumber";
    /** {@code qa40x.ranges}: the selectable input full-scale values. */
    public static final String INPUT_DBV = "inputDbv";
    /** {@code qa40x.ranges}: the selectable output full-scale values. */
    public static final String OUTPUT_DBV = "outputDbv";
    /** {@code qa40x.ranges}: the input range in force. */
    public static final String ACTIVE_INPUT_DBV = "activeInputDbv";
    /** {@code qa40x.ranges}: the output range in force. */
    public static final String ACTIVE_OUTPUT_DBV = "activeOutputDbv";
    /** A full-scale range in dBV - the range setters and a calibration row. */
    public static final String DBV = "dbv";
    /** {@code qa40x.calibration}: the ADC rows. */
    public static final String ADC = "adc";
    /** {@code qa40x.calibration}: the DAC rows. */
    public static final String DAC = "dac";
    /** Calibration row: the left channel's linear factor. */
    public static final String LEFT = "left";
    /** Calibration row: the right channel's linear factor. */
    public static final String RIGHT = "right";
    /** {@code qa40x.settings}: front-panel I2S port state; it moves the
     *  supported bit depths exactly as it does locally. */
    public static final String I2S_ENABLED = "i2sEnabled";
}
