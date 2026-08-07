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

package org.edgo.audio.measure.sound.qa40x;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;

import lombok.Setter;
import lombok.extern.log4j.Log4j2;

import org.edgo.audio.measure.sound.LibUsb;

/**
 * {@link Qa40xTransport} over {@link LibUsb} for an already-opened QA402/QA403
 * device.  Constructed around a claimed device handle (the finder does the
 * open / reset / claim); this class only moves bytes.
 *
 * <p><b>Registers</b> go through synchronous bulk transfers with a 1000 ms
 * timeout (doc §4): a write is the 5-byte big-endian frame on EP {@code 0x01};
 * a read writes {@code 0x80|reg} then reads the 4-byte big-endian reply from
 * EP {@code 0x81}.  The frame codec is the canonical, unit-tested
 * {@link Qa40xProtocol} - this class only moves the bytes it produces.
 *
 * <p><b>Audio</b> goes through asynchronous transfers.  Each
 * {@link #submitAudioWrite}/{@link #submitAudioRead} allocates a
 * {@code libusb_transfer}, copies into / wraps a native {@link Memory} buffer and
 * submits it; a single event thread ({@value #EVENT_THREAD_NAME},
 * {@link Thread#MAX_PRIORITY}) pumps {@code libusb_handle_events} and dispatches
 * completions to the {@link TransferListener}.  The engine keeps ≥2 transfers in
 * flight per direction (doc §5) by submitting more than one; the transport tracks
 * every in-flight transfer so {@link #cancelAll()} and {@link #close()} can drain
 * them.
 *
 * <p><b>Stop discipline (doc §3/§7).</b> Stopping cancels in-flight transfers and
 * joins the event thread; it never resets or clear-halts a pipe - that hangs the
 * next session's first read.
 */
@Log4j2
public final class LibUsbQa40xTransport implements Qa40xTransport {

    /** Interface index - the only interface used on QA402/QA403 (doc §3). */
    static final int  INTERFACE_0        = 0;

    static final byte REG_OUT_EP         = (byte) 0x01;
    static final byte REG_IN_EP          = (byte) 0x81;
    static final byte AUDIO_OUT_EP       = (byte) 0x02;
    static final byte AUDIO_IN_EP        = (byte) 0x82;

    /** All PyQa40x bulk calls use a 1000 ms timeout (doc §4/§5). */
    static final int  REG_TIMEOUT_MS      = 1000;

    private static final String EVENT_THREAD_NAME = "qa40x-usb";
    /** {@code handle_events} wake interval - long enough to be idle-cheap, short enough to stop promptly. */
    private static final int    EVENT_TIMEOUT_US  = 100_000;
    private static final long   CLOSE_JOIN_MS     = 2_000L;

    private final LibUsb.Lib lib;
    // stdcallSafe: on 32-bit Windows libusb invokes the callback WINAPI (stdcall);
    // an unmarked (cdecl) JNA thunk there corrupts the stack - see LibUsb.
    private final LibUsb.TransferCallback transferCallback = LibUsb.stdcallSafe(this::onTransferComplete);
    /** In-flight async transfers, keyed by the {@code libusb_transfer} pointer. */
    private final Map<Pointer, TransferContext> activeTransfers = new ConcurrentHashMap<>();

    private volatile Pointer handle;                          // libusb_device_handle*, nulled on close
    @Setter private volatile TransferListener listener;       // Qa40xTransport#setListener
    private volatile Thread  eventThread;
    private volatile boolean running;

    public LibUsbQa40xTransport(LibUsb.Lib lib, Pointer handle) {
        this.lib    = lib;
        this.handle = handle;
    }

    @Override
    public void registerWrite(int reg, int value) {
        bulkOut(REG_OUT_EP, Qa40xProtocol.writeFrame(reg, value), "registerWrite(0x" + Integer.toHexString(reg) + ")");
    }

    @Override
    public int registerRead(int reg) {
        bulkOut(REG_OUT_EP, Qa40xProtocol.readRequestFrame(reg), "registerRead-request(0x" + Integer.toHexString(reg) + ")");
        byte[] reply = new byte[Qa40xProtocol.REGISTER_REPLY_BYTES];
        int got = bulkIn(REG_IN_EP, reply, "registerRead-reply(0x" + Integer.toHexString(reg) + ")");
        if (got != Qa40xProtocol.REGISTER_REPLY_BYTES) {
            throw new IllegalStateException("registerRead(0x" + Integer.toHexString(reg)
                    + "): expected " + Qa40xProtocol.REGISTER_REPLY_BYTES + " bytes, got " + got);
        }
        return Qa40xProtocol.decodeReply(reply);
    }

    @Override
    public void submitAudioWrite(byte[] data, int length) {
        ensureEventLoop();
        Memory buffer = new Memory(length);
        buffer.write(0, data, 0, length);
        submitAsync(AUDIO_OUT_EP, buffer, length, false, data);
    }

    @Override
    public void submitAudioRead(byte[] buffer) {
        ensureEventLoop();
        Memory nativeBuffer = new Memory(buffer.length);
        submitAsync(AUDIO_IN_EP, nativeBuffer, buffer.length, true, buffer);
    }

    @Override
    public void cancelAll() {
        for (Pointer transfer : activeTransfers.keySet()) {
            int rc = lib.libusb_cancel_transfer(transfer);
            // A negative code here usually means the transfer already completed -
            // harmless; log only at debug so a normal stop stays quiet.
            if (rc < 0 && log.isDebugEnabled()) {
                log.debug("libusb_cancel_transfer: {}", LibUsb.errorName(rc));
            }
        }
    }

    /**
     * Gives the device back.  Every step runs, whatever the step before it did:
     * this is the close a manager calls precisely BECAUSE the analyzer stopped
     * answering, so a cancel or a join that faults must not be what keeps the
     * interface claimed and the handle open for the rest of the process's life.
     */
    @Override
    public void close() {
        try {
            cancelAll();
        } finally {
            running = false;
            try {
                joinEventThread();
            } finally {
                releaseHandle();
            }
        }
    }

    private void joinEventThread() {
        Thread t = eventThread;
        if (t != null) {
            try {
                t.join(CLOSE_JOIN_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            eventThread = null;
        }
        int leaked = activeTransfers.size();
        if (leaked > 0) {
            // The event thread frees each transfer in its completion callback; a
            // non-empty map here means it did not drain within the join window.
            // Do NOT free them from this thread - that races the still-running
            // native transfer.  The event loop gives up on them too (it is bounded
            // by the same window), so the thread ends instead of spinning for the
            // life of the process on completions a detached device will never send.
            log.warn("qa40x-usb: {} transfer(s) still in flight at close()", leaked);
        }
    }

    private void releaseHandle() {
        Pointer h = handle;
        if (h == null) {
            return;
        }
        try {
            int rc = lib.libusb_release_interface(h, INTERFACE_0);
            if (rc < 0) {
                log.warn("libusb_release_interface: {}", LibUsb.errorName(rc));
            }
            lib.libusb_close(h);
        } finally {
            // Cleared whatever the release did: a handle left in the field is one
            // the next register write would happily submit to a device that is
            // already gone.
            handle = null;
        }
    }

    // --- sync bulk (registers + calibration) ---------------------------------

    private void bulkOut(byte endpoint, byte[] frame, String op) {
        IntByReference transferred = new IntByReference();
        int rc = bulk(endpoint, frame, frame.length, transferred, op);
        if (rc < 0) {
            throw new IllegalStateException(op + " failed: " + LibUsb.errorName(rc));
        }
    }

    private int bulkIn(byte endpoint, byte[] buffer, String op) {
        IntByReference transferred = new IntByReference();
        int rc = bulk(endpoint, buffer, buffer.length, transferred, op);
        if (rc < 0) {
            throw new IllegalStateException(op + " failed: " + LibUsb.errorName(rc));
        }
        return transferred.getValue();
    }

    /**
     * The ONE place a synchronous bulk transfer is invoked, and the one place a
     * native fault becomes this transport's documented failure.
     *
     * <p>A return code below zero is only how {@code libusb} reports a device it
     * still has a handle on.  Pull the analyzer's cable and the invocation itself
     * faults: JNA raises an Error ("Invalid memory access" out of
     * {@code libusb_bulk_transfer}), the {@code rc < 0} test
     * above never runs, and an Error walks straight through every
     * {@code catch (RuntimeException)} in the stack - which is how a dead handle
     * was kept, a play thread died silently and a server lock was never given
     * back.  Converting here means every caller's existing handling of "this
     * transport refused" covers a device that has physically gone, without any of
     * them having to know what JNA does.
     */
    private int bulk(byte endpoint, byte[] buffer, int length, IntByReference transferred, String op) {
        try {
            return lib.libusb_bulk_transfer(handle, endpoint, buffer, length, transferred, REG_TIMEOUT_MS);
        } catch (Throwable t) {
            throw new IllegalStateException(op + " failed in the native call "
                    + "(the device is gone?): " + t, t);
        }
    }

    // --- async audio ---------------------------------------------------------

    private void submitAsync(byte endpoint, Memory buffer, int length, boolean read, byte[] javaBuffer) {
        Pointer p = lib.libusb_alloc_transfer(0);
        if (p == null) {
            throw new IllegalStateException("libusb_alloc_transfer returned null");
        }
        LibUsb.LibUsbTransfer transfer = new LibUsb.LibUsbTransfer(p);
        transfer.devHandle     = handle;
        transfer.endpoint      = endpoint;
        transfer.type          = LibUsb.TRANSFER_TYPE_BULK;
        transfer.timeout       = 0;                 // streaming: no per-transfer timeout
        transfer.length        = length;
        transfer.callback      = transferCallback;
        transfer.buffer        = buffer;
        transfer.userData      = null;
        transfer.numIsoPackets = 0;

        TransferContext ctx = new TransferContext(transfer, buffer, javaBuffer, read);
        activeTransfers.put(p, ctx);
        transfer.write();
        int rc = lib.libusb_submit_transfer(p);
        if (rc < 0) {
            activeTransfers.remove(p);
            lib.libusb_free_transfer(p);
            throw new IllegalStateException("libusb_submit_transfer failed: " + LibUsb.errorName(rc));
        }
    }

    /** Completion callback, invoked by {@code libusb} on the event thread. */
    private void onTransferComplete(Pointer transferPtr) {
        TransferContext ctx = activeTransfers.remove(transferPtr);
        if (ctx == null) {
            return;
        }
        try {
            // Read back only the two fields the device updates - a full read()
            // would also re-map the native callback function-pointer field.
            ctx.transfer.readField("status");
            ctx.transfer.readField("actualLength");
            int status = ctx.transfer.status;
            int actual = ctx.transfer.actualLength;
            TransferListener l = listener;
            if (status == LibUsb.TRANSFER_COMPLETED) {
                if (ctx.read) {
                    ctx.buffer.read(0, ctx.javaBuffer, 0, actual);
                    if (l != null) l.readCompleted(ctx.javaBuffer, actual);
                } else if (l != null) {
                    l.writeCompleted(ctx.javaBuffer, actual);
                }
            } else if (l != null) {
                l.transferFailed(ctx.read, LibUsb.transferStatusName(status));
            }
        } catch (Throwable th) {
            log.error("qa40x-usb transfer callback failed: {}", th.toString(), th);
        } finally {
            lib.libusb_free_transfer(transferPtr);
        }
    }

    private synchronized void ensureEventLoop() {
        if (eventThread == null) {
            running = true;
            Thread t = new Thread(this::eventLoop, EVENT_THREAD_NAME);
            t.setDaemon(true);
            t.setPriority(Thread.MAX_PRIORITY);
            eventThread = t;
            t.start();
        }
    }

    /**
     * The event thread's body, and therefore a thread boundary: anything that
     * escapes it kills the only thread that delivers completions, and a stream
     * whose pacing clock has died goes on looking like it is running.  So the
     * whole loop is guarded and the failure is logged where it happened.
     */
    private void eventLoop() {
        LibUsb.Timeval tv = new LibUsb.Timeval();
        tv.tvUsec.setValue(EVENT_TIMEOUT_US);
        tv.write();
        try {
            while (running) {
                pumpEvents(tv);
            }
            // Afterwards, keep pumping until the cancelled transfers have reported
            // (so their memory is freed) - but only for as long as close() waits.
            // A device that was pulled out never completes them at all, and an
            // unbounded drain there is a maximum-priority thread spinning on
            // libusb for the life of the process, one per dead session.
            long deadline = System.currentTimeMillis() + CLOSE_JOIN_MS;
            while (!activeTransfers.isEmpty() && System.currentTimeMillis() < deadline) {
                pumpEvents(tv);
            }
        } catch (Throwable t) {
            log.error("qa40x-usb event loop ended on a fault: {}", t.toString(), t);
        }
    }

    private void pumpEvents(LibUsb.Timeval tv) {
        int rc = lib.libusb_handle_events_timeout_completed(null, tv, null);
        if (rc < 0 && rc != LibUsb.ERROR_INTERRUPTED) {
            log.warn("libusb_handle_events: {}", LibUsb.errorName(rc));
        }
    }

    /** Per-transfer bookkeeping: keeps the native buffer + Java buffer alive until completion. */
    private static final class TransferContext {
        final LibUsb.LibUsbTransfer transfer;
        final Memory  buffer;       // native buffer libusb reads/writes
        final byte[]  javaBuffer;   // caller's buffer: source (write) or destination (read)
        final boolean read;

        TransferContext(LibUsb.LibUsbTransfer transfer, Memory buffer, byte[] javaBuffer, boolean read) {
            this.transfer   = transfer;
            this.buffer     = buffer;
            this.javaBuffer = javaBuffer;
            this.read       = read;
        }
    }
}
