/*
 * Phonalyser — precision audio measurement workbench.
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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

import lombok.extern.log4j.Log4j2;

import org.edgo.audio.measure.sound.LibUsb;

/**
 * Enumerates attached QA402/QA403 analyzers and opens exactly one, exclusively.
 * Reaches the native binding through {@link LibUsb#lib()} / {@link LibUsb#available()}
 * just as {@code CoreAudioDeviceManager} reaches {@code PortAudio.lib()}.
 *
 * <p>All QA40x models share USB vendor ID {@code 0x16C0}; the product ID selects
 * the model (doc §2).  {@link #list()} degrades gracefully to an empty list when
 * {@code libusb} is absent; {@link #open()} enforces the single-device rule —
 * like ASIO401 it refuses to run with more than one QA40x attached (doc §2/§7) —
 * and does the {@code reset_device} + {@code claim_interface(0)} that PyQa40x does
 * on open, handing the claimed handle to a {@link LibUsbQa40xTransport}.
 */
@Log4j2
public final class Qa40xDeviceFinder {

    /** Shared Van Ooijen (V-USB / LibUSB) vendor ID used by every QA40x (doc §2). */
    static final int QA_VID = 0x16C0;

    /** {@code libusb_free_device_list} unref flag — release the device references we did not open. */
    private static final int UNREF_DEVICES = 1;

    /** The QA40x's one and only USB configuration — selected explicitly before the
     *  claim because macOS, unlike Linux / Windows, does not auto-configure. */
    private static final int ACTIVE_CONFIGURATION = 1;

    /** Open attempts and the settle pause between them.  macOS: {@code
     *  libusb_reset_device} drops and re-enumerates the device, so the first
     *  configure/claim can race the re-enumeration — the interface (or the whole
     *  device) is momentarily gone: {@code LIBUSB_ERROR_NOT_FOUND} on the claim,
     *  or an empty device list on the next pass.  A short settle plus a fresh
     *  open WITHOUT another reset recovers; Linux / Windows restore state
     *  synchronously and never retry in practice. */
    private static final int  OPEN_ATTEMPTS   = 3;
    private static final long RESET_SETTLE_MS = 250;

    /** A QA40x model and its USB product ID (doc §2).  QA401 is deliberately out of scope. */
    public enum Qa40xModel {
        QA402(0x4E37),
        QA403(0x4E39);

        private final int productId;

        Qa40xModel(int productId) {
            this.productId = productId;
        }

        /** Resolves the model from a USB product ID, or empty if it is not a QA402/QA403. */
        public static Optional<Qa40xModel> fromProductId(int pid) {
            for (Qa40xModel model : values()) {
                if (model.productId == pid) {
                    return Optional.of(model);
                }
            }
            return Optional.empty();
        }
    }

    /** An attached QA40x: its model plus USB bus/address (the "model + address" the finder reports). */
    public record Qa40xDevice(Qa40xModel model, int busNumber, int address) {
        @Override
        public String toString() {
            return model + " @ bus " + busNumber + " addr " + address;
        }
    }

    /** Lists attached QA402/QA403 devices; empty when {@code libusb} is unavailable. */
    public List<Qa40xDevice> list() {
        if (!LibUsb.available()) {
            return List.of();
        }
        return withDeviceList((lib, devices) -> {
            List<Qa40xDevice> found = new ArrayList<>();
            for (Pointer dev : devices) {
                modelOf(lib, dev).ifPresent(model -> found.add(new Qa40xDevice(
                        model,
                        lib.libusb_get_bus_number(dev)     & 0xFF,
                        lib.libusb_get_device_address(dev) & 0xFF)));
            }
            return found;
        });
    }

    /**
     * Opens the single attached QA40x exclusively: {@code reset_device} then
     * {@code claim_interface(0)} (doc §7).  Throws if {@code libusb} is absent, if
     * no device is attached, or if more than one is (the single-device rule).
     * The reset runs only on the FIRST attempt; a retry re-opens without it —
     * resetting again would just re-arm the macOS re-enumeration race the retry
     * is there to escape (see {@link #OPEN_ATTEMPTS}).
     */
    public LibUsbQa40xTransport open() {
        if (!LibUsb.available()) {
            throw new IllegalStateException("libusb-1.0 not available — cannot open a QA40x device");
        }
        IllegalStateException last = null;
        for (int attempt = 1; attempt <= OPEN_ATTEMPTS; attempt++) {
            try {
                return openOnce(attempt == 1);
            } catch (IllegalStateException e) {
                last = e;
                if (attempt < OPEN_ATTEMPTS) {
                    if (log.isInfoEnabled()) {
                        log.info("QA40x open attempt {}/{} failed ({}); settling {} ms before retry",
                                attempt, OPEN_ATTEMPTS, e.getMessage(), RESET_SETTLE_MS);
                    }
                    settleBeforeRetry();
                }
            }
        }
        throw last;
    }

    /**
     * One pass of the open dance: {@code libusb_open}, optional {@code
     * reset_device}, configuration select, {@code claim_interface(0)}.  The
     * handle is closed on ANY failure — an opened-but-unclaimed handle left
     * dangling on a re-enumerating device poisons the next attempt.
     */
    private LibUsbQa40xTransport openOnce(boolean reset) {
        return withDeviceList((lib, devices) -> {
            Pointer match = null;
            Qa40xModel model = null;
            int count = 0;
            for (Pointer dev : devices) {
                Optional<Qa40xModel> m = modelOf(lib, dev);
                if (m.isPresent()) {
                    count++;
                    match = dev;
                    model = m.get();
                }
            }
            requireSingle(count);

            PointerByReference handleRef = new PointerByReference();
            checkRc(lib.libusb_open(match, handleRef), "libusb_open");
            Pointer handle = handleRef.getValue();
            boolean claimed = false;
            try {
                if (reset) {
                    checkRc(lib.libusb_reset_device(handle), "libusb_reset_device");
                }
                // macOS: unlike Linux / Windows the OS does not auto-select the
                // device configuration — after enumeration (or the reset-induced
                // re-enumeration above) the QA40x can sit UNCONFIGURED, and claiming
                // interface 0 of configuration 0 fails with LIBUSB_ERROR_NOT_FOUND.
                // Select the device's only configuration first; a no-op wherever the
                // OS already configured it.
                IntByReference cfg = new IntByReference();
                checkRc(lib.libusb_get_configuration(handle, cfg), "libusb_get_configuration");
                if (cfg.getValue() != ACTIVE_CONFIGURATION) {
                    checkRc(lib.libusb_set_configuration(handle, ACTIVE_CONFIGURATION), "libusb_set_configuration");
                }
                checkRc(lib.libusb_claim_interface(handle, LibUsbQa40xTransport.INTERFACE_0), "libusb_claim_interface(0)");
                claimed = true;
            } finally {
                if (!claimed) {
                    lib.libusb_close(handle);
                }
            }
            log.info("Opened {} (bus {}, addr {})", model,
                    lib.libusb_get_bus_number(match)     & 0xFF,
                    lib.libusb_get_device_address(match) & 0xFF);
            return new LibUsbQa40xTransport(lib, handle);
        });
    }

    /** Waits {@link #RESET_SETTLE_MS} between open attempts; an interrupt aborts
     *  the open (flag restored) rather than shortening the pause. */
    private void settleBeforeRetry() {
        try {
            Thread.sleep(RESET_SETTLE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the QA40x to re-enumerate", e);
        }
    }

    /** Enforces the single-device rule (doc §2/§7); pure, so it is unit-tested directly. */
    void requireSingle(int count) {
        if (count == 0) {
            throw new IllegalStateException("No QA402/QA403 found on USB");
        }
        if (count > 1) {
            throw new IllegalStateException(count + " QA40x devices attached — connect exactly one "
                    + "(the QA40x backend, like ASIO401, drives a single device)");
        }
    }

    /** Reads a device descriptor and maps it to a QA40x model, or empty if it is not one. */
    private Optional<Qa40xModel> modelOf(LibUsb.Lib lib, Pointer device) {
        LibUsb.LibUsbDeviceDescriptor desc = new LibUsb.LibUsbDeviceDescriptor();
        if (lib.libusb_get_device_descriptor(device, desc) < 0) {
            return Optional.empty();
        }
        desc.read();
        if ((desc.idVendor & 0xFFFF) != QA_VID) {
            return Optional.empty();
        }
        return Qa40xModel.fromProductId(desc.idProduct & 0xFFFF);
    }

    /**
     * Acquires the {@code libusb} device list, runs {@code body} over the device
     * pointers, and frees the list afterwards — the one place the list lifetime is
     * managed, shared by {@link #list()} and {@link #open()}.
     */
    private <T> T withDeviceList(DeviceListBody<T> body) {
        LibUsb.Lib lib = LibUsb.lib();
        PointerByReference listRef = new PointerByReference();
        NativeLong count = lib.libusb_get_device_list(null, listRef);
        long n = count.longValue();
        if (n < 0) {
            throw new IllegalStateException("libusb_get_device_list failed: " + LibUsb.errorName((int) n));
        }
        Pointer head = listRef.getValue();
        try {
            Pointer[] devices = (n == 0 || head == null) ? new Pointer[0] : head.getPointerArray(0, (int) n);
            return body.apply(lib, devices);
        } finally {
            if (head != null) {
                lib.libusb_free_device_list(head, UNREF_DEVICES);
            }
        }
    }

    private void checkRc(int rc, String op) {
        if (rc < 0) {
            throw new IllegalStateException(op + " failed: " + LibUsb.errorName(rc));
        }
    }

    /** Body run against the live device list by {@link #withDeviceList}. */
    @FunctionalInterface
    private interface DeviceListBody<T> {
        T apply(LibUsb.Lib lib, Pointer[] devices);
    }
}
