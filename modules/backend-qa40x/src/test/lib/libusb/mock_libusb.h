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

/*
 * mock_libusb - the libusb-1.0 ABI subset that the Phonalyser JNA binding
 * (src/main/java/org/edgo/audio/measure/sound/LibUsb.java) actually calls, plus
 * the exact struct layouts JNA marshals.  x64 only: __stdcall == __cdecl, so the
 * JNA cdecl mapping and libusb's WINAPI-style LIBUSB_CALL are indistinguishable.
 *
 * The nineteen exported entry points implement that subset; the wire contract
 * they carry is in doc/QA40X-PROTOCOL.md.
 */
#ifndef MOCK_LIBUSB_H
#define MOCK_LIBUSB_H

#include <stdint.h>

/* System headers at /W3 so the DLL's own code can stay warning-clean at /W4. */
#pragma warning(push, 3)
#include <winsock2.h>   /* struct timeval (2 x 32-bit long on Windows) */
#include <windows.h>    /* WINAPI, threading, CRITICAL_SECTION, CONDITION_VARIABLE */
#pragma warning(pop)
/* timeBeginPeriod / timeEndPeriod are declared where mock_libusb.c uses them
 * (winmm.lib) - their SDK header moved between VS versions, so mock_libusb.c
 * declares the two prototypes it needs directly. */

/* Exported undecorated on x64 via __declspec(dllexport) - no .def needed. */
#define MOCK_API      __declspec(dllexport)
/* libusb's public calling convention; on x64 WINAPI == the JNA cdecl mapping. */
#define LIBUSB_CALL   WINAPI

/* --- libusb_error (subset, doc/plan) ------------------------------------- */
#define LIBUSB_SUCCESS                 0
#define LIBUSB_ERROR_IO               -1
#define LIBUSB_ERROR_INVALID_PARAM    -2
#define LIBUSB_ERROR_ACCESS           -3
#define LIBUSB_ERROR_NO_DEVICE        -4
#define LIBUSB_ERROR_NOT_FOUND        -5
#define LIBUSB_ERROR_BUSY             -6
#define LIBUSB_ERROR_TIMEOUT          -7
#define LIBUSB_ERROR_INTERRUPTED     -10
#define LIBUSB_ERROR_NO_MEM          -11
#define LIBUSB_ERROR_NOT_SUPPORTED   -12
#define LIBUSB_ERROR_OTHER           -99

/* --- libusb_transfer_status ---------------------------------------------- */
#define LIBUSB_TRANSFER_COMPLETED      0
#define LIBUSB_TRANSFER_ERROR          1
#define LIBUSB_TRANSFER_TIMED_OUT      2
#define LIBUSB_TRANSFER_CANCELLED      3
#define LIBUSB_TRANSFER_STALL          4
#define LIBUSB_TRANSFER_NO_DEVICE      5
#define LIBUSB_TRANSFER_OVERFLOW       6

/* --- libusb_transfer_type ------------------------------------------------ */
#define LIBUSB_TRANSFER_TYPE_BULK      2

/* Opaque handle types - callers (and JNA) only ever hold pointers. */
typedef struct libusb_context        libusb_context;
typedef struct libusb_device         libusb_device;
typedef struct libusb_device_handle  libusb_device_handle;

/*
 * struct libusb_device_descriptor - the standard 18-byte USB device descriptor,
 * matching JNA LibUsb.LibUsbDeviceDescriptor (u8,u8,u16,... natural alignment).
 */
struct libusb_device_descriptor {
    uint8_t  bLength;
    uint8_t  bDescriptorType;
    uint16_t bcdUSB;
    uint8_t  bDeviceClass;
    uint8_t  bDeviceSubClass;
    uint8_t  bDeviceProtocol;
    uint8_t  bMaxPacketSize0;
    uint16_t idVendor;
    uint16_t idProduct;
    uint16_t bcdDevice;
    uint8_t  iManufacturer;
    uint8_t  iProduct;
    uint8_t  iSerialNumber;
    uint8_t  bNumConfigurations;
};

struct libusb_transfer;
typedef void (LIBUSB_CALL *libusb_transfer_cb_fn)(struct libusb_transfer *transfer);

/*
 * struct libusb_transfer - the async control block, matching JNA
 * LibUsb.LibUsbTransfer field order with MSVC natural alignment (x64 offsets:
 * dev_handle 0, flags 8, endpoint 9, type 10, timeout 12, status 16, length 20,
 * actual_length 24, callback 32, user_data 40, buffer 48, num_iso_packets 56;
 * sizeof == 64).  mock_libusb.c pins every offset with C_ASSERT.
 */
struct libusb_transfer {
    libusb_device_handle *dev_handle;
    uint8_t               flags;
    unsigned char         endpoint;
    unsigned char         type;
    int                   timeout;
    int                   status;
    int                   length;
    int                   actual_length;
    libusb_transfer_cb_fn callback;
    void                 *user_data;
    unsigned char        *buffer;
    int                   num_iso_packets;
};

/* --- the 21 exported entry points (see LibUsb.java Lib) ------------------- */
MOCK_API int          LIBUSB_CALL libusb_init(libusb_context **ctx);
MOCK_API void         LIBUSB_CALL libusb_exit(libusb_context *ctx);
MOCK_API const char * LIBUSB_CALL libusb_error_name(int errcode);

MOCK_API intptr_t     LIBUSB_CALL libusb_get_device_list(libusb_context *ctx, libusb_device ***list);
MOCK_API void         LIBUSB_CALL libusb_free_device_list(libusb_device **list, int unref_devices);
MOCK_API int          LIBUSB_CALL libusb_get_device_descriptor(libusb_device *dev,
                                          struct libusb_device_descriptor *desc);
MOCK_API uint8_t      LIBUSB_CALL libusb_get_bus_number(libusb_device *dev);
MOCK_API uint8_t      LIBUSB_CALL libusb_get_device_address(libusb_device *dev);

MOCK_API int          LIBUSB_CALL libusb_open(libusb_device *dev, libusb_device_handle **handle);
MOCK_API void         LIBUSB_CALL libusb_close(libusb_device_handle *handle);
MOCK_API int          LIBUSB_CALL libusb_reset_device(libusb_device_handle *handle);
MOCK_API int          LIBUSB_CALL libusb_get_configuration(libusb_device_handle *handle, int *configuration);
MOCK_API int          LIBUSB_CALL libusb_set_configuration(libusb_device_handle *handle, int configuration);
MOCK_API int          LIBUSB_CALL libusb_claim_interface(libusb_device_handle *handle, int interface_number);
MOCK_API int          LIBUSB_CALL libusb_release_interface(libusb_device_handle *handle, int interface_number);

MOCK_API int          LIBUSB_CALL libusb_bulk_transfer(libusb_device_handle *handle, unsigned char endpoint,
                                          unsigned char *data, int length, int *transferred, unsigned int timeout);

MOCK_API struct libusb_transfer * LIBUSB_CALL libusb_alloc_transfer(int iso_packets);
MOCK_API void         LIBUSB_CALL libusb_free_transfer(struct libusb_transfer *transfer);
MOCK_API int          LIBUSB_CALL libusb_submit_transfer(struct libusb_transfer *transfer);
MOCK_API int          LIBUSB_CALL libusb_cancel_transfer(struct libusb_transfer *transfer);
MOCK_API int          LIBUSB_CALL libusb_handle_events_timeout_completed(libusb_context *ctx,
                                          struct timeval *tv, int *completed);

#endif /* MOCK_LIBUSB_H */
