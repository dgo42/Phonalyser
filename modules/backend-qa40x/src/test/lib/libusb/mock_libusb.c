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
 * mock_libusb - the libusb-1.0 API plumbing: init/exit + refcount, the single
 * device list, handles, the async transfer lifecycle, the register bulk path,
 * and the sample-clock worker thread that bridges libusb transfers into and out
 * of the emulated QA403 (mock_qa403).  The wire contract the emulation follows
 * is in doc/QA40X-PROTOCOL.md.
 *
 * Locking discipline: ONE global CRITICAL_SECTION guards all state.  Completion
 * callbacks are invoked ONLY inside libusb_handle_events_timeout_completed, on
 * the calling thread, NEVER while holding the lock - the Java callback re-enters
 * libusb_submit_transfer / libusb_free_transfer.
 */

#include "mock_libusb.h"
#include "mock_qa403.h"

#include <stddef.h>   /* offsetof */
#include <stdlib.h>   /* malloc/calloc/free */

#pragma comment(lib, "winmm.lib")

/* Declared directly: the SDK header for these moved between VS versions. */
__declspec(dllimport) UINT WINAPI timeBeginPeriod(UINT uPeriod);
__declspec(dllimport) UINT WINAPI timeEndPeriod(UINT uPeriod);

/* Opt this process out of Windows 11 background timer-resolution throttling so
 * the worker's Sleep(1) / timeBeginPeriod(1) stays fine-grained even when the app
 * has NO foreground window.  Otherwise EcoQoS ignores timeBeginPeriod(1) for a
 * backgrounded process, Sleep(1) coarsens to ~15.6 ms, and the sample clock
 * produces one ~15.6 ms burst per tick - which the app's ~21 ms DAC buffer can't
 * smooth, giving periodic loopback underruns.
 * SetProcessInformation + ProcessPowerThrottling postdate the VS2015 SDK, so the
 * call is resolved dynamically and simply no-ops on older Windows. */
typedef struct {
    ULONG Version;
    ULONG ControlMask;
    ULONG StateMask;
} MOCK_POWER_THROTTLING_STATE;
#define MOCK_PROCESS_POWER_THROTTLING          4      /* PROCESS_INFORMATION_CLASS */
#define MOCK_POWER_THROTTLING_VERSION_1        1
#define MOCK_POWER_THROTTLING_IGNORE_TIMER_RES 0x4

static void ignore_timer_throttling(void)
{
    typedef BOOL (WINAPI *set_proc_info_fn)(HANDLE, int, LPVOID, DWORD);
    HMODULE k32 = GetModuleHandleW(L"kernel32.dll");
    set_proc_info_fn set_info =
        k32 ? (set_proc_info_fn)GetProcAddress(k32, "SetProcessInformation") : NULL;
    if (set_info != NULL) {
        MOCK_POWER_THROTTLING_STATE pt;
        pt.Version     = MOCK_POWER_THROTTLING_VERSION_1;
        pt.ControlMask = MOCK_POWER_THROTTLING_IGNORE_TIMER_RES;
        pt.StateMask   = 0;   /* 0 = do NOT ignore our timer resolution (honor it) */
        set_info(GetCurrentProcess(), MOCK_PROCESS_POWER_THROTTLING, &pt, sizeof(pt));
    }
}

/* --- USB identity (doc section 2; QA403, deliberately NOT QA402) ---------- */
#define QA_VID          0x16C0
#define QA_PID_QA403    0x4E39
#define QA_BUS_NUMBER   1
#define QA_DEV_ADDRESS  5
/* bConfigurationValue of the device's only configuration.  Distinct from the
 * descriptor's bNumConfigurations (a COUNT that happens to be 1 as well): this
 * is the VALUE the host selects and reads back. */
#define QA403_CONFIGURATION 1

/* --- endpoints (doc section 3) ------------------------------------------- */
#define EP_REG_OUT      0x01
#define EP_REG_IN       0x81
#define EP_AUDIO_OUT    0x02
#define EP_AUDIO_IN     0x82

#define FRAME_BYTES     8     /* 2 channels x int32 (doc section 5) */
#define REG_FRAME_BYTES 5     /* register write / read-request frame (doc section 4) */
#define REG_REPLY_BYTES 4     /* register read reply (doc section 4) */

/* Opaque device-handle body: only its address is meaningful. */
typedef struct mock_handle {
    int reserved;
} mock_handle;

/*
 * A submitted transfer.  The public struct is FIRST so the pointer libusb hands
 * out (== &pub) is also the malloc block start: free(transfer) frees the whole
 * wrapper, and (mock_transfer *)transfer recovers it.
 */
typedef struct mock_transfer {
    struct libusb_transfer pub;   /* MUST be first */
    struct mock_transfer  *next;  /* pending / completed queue link */
    int                    progress; /* OUT transfers: bytes fed so far */
} mock_transfer;

/* Global context, all fields guarded by lock. */
typedef struct mock_ctx {
    CRITICAL_SECTION   lock;
    CONDITION_VARIABLE cv;
    HANDLE             worker;
    volatile LONG      worker_stop;

    qa403_device       dev;

    mock_transfer     *out_head, *out_tail;   /* pending EP 0x02 (DAC OUT) */
    mock_transfer     *in_head,  *in_tail;    /* pending EP 0x82 (ADC IN) */
    mock_transfer     *done_head, *done_tail; /* completed, awaiting dispatch */

    mock_handle       *claimed_by;            /* interface-0 claim owner, or NULL */

    LARGE_INTEGER      qpc_freq;
    LARGE_INTEGER      qpc_last;
} mock_ctx;

static mock_ctx g_ctx;
static LONG     g_init_count = 0;
static char     g_device;     /* the single emulated device token */

/* Compile-time layout pins: a drift fails the build, not the JVM. */
C_ASSERT(sizeof(struct libusb_transfer) == 64);
C_ASSERT(offsetof(struct libusb_transfer, dev_handle) == 0);
C_ASSERT(offsetof(struct libusb_transfer, flags) == 8);
C_ASSERT(offsetof(struct libusb_transfer, endpoint) == 9);
C_ASSERT(offsetof(struct libusb_transfer, type) == 10);
C_ASSERT(offsetof(struct libusb_transfer, timeout) == 12);
C_ASSERT(offsetof(struct libusb_transfer, status) == 16);
C_ASSERT(offsetof(struct libusb_transfer, length) == 20);
C_ASSERT(offsetof(struct libusb_transfer, actual_length) == 24);
C_ASSERT(offsetof(struct libusb_transfer, callback) == 32);
C_ASSERT(offsetof(struct libusb_transfer, user_data) == 40);
C_ASSERT(offsetof(struct libusb_transfer, buffer) == 48);
C_ASSERT(offsetof(struct libusb_transfer, num_iso_packets) == 56);
C_ASSERT(sizeof(struct libusb_device_descriptor) == 18);
C_ASSERT(offsetof(mock_transfer, pub) == 0);

/* --- queue helpers (all assume the lock is held) ------------------------- */

static void enqueue(mock_transfer **head, mock_transfer **tail, mock_transfer *m)
{
    m->next = NULL;
    if (*tail != NULL) {
        (*tail)->next = m;
    } else {
        *head = m;
    }
    *tail = m;
}

/* Unlinks m from a pending FIFO; returns 1 if it was there. */
static int remove_pending(mock_transfer **head, mock_transfer **tail, mock_transfer *m)
{
    mock_transfer *cur = *head, *prev = NULL;
    while (cur != NULL) {
        if (cur == m) {
            if (prev == NULL) {
                *head = cur->next;
            } else {
                prev->next = cur->next;
            }
            if (*tail == cur) {
                *tail = prev;
            }
            return 1;
        }
        prev = cur;
        cur = cur->next;
    }
    return 0;
}

/* Records the outcome and moves m to the completed queue, waking the CV. */
static void complete_locked(mock_transfer *m, int status, int actual_length)
{
    m->pub.status = status;
    m->pub.actual_length = actual_length;
    m->next = NULL;
    if (g_ctx.done_tail != NULL) {
        g_ctx.done_tail->next = m;
    } else {
        g_ctx.done_head = m;
    }
    g_ctx.done_tail = m;
    WakeAllConditionVariable(&g_ctx.cv);
}

/* Completes every pending transfer of a given handle with NO_DEVICE. */
static void fail_pending_for_handle(mock_transfer **head, mock_transfer **tail, mock_handle *handle)
{
    mock_transfer *cur = *head, *prev = NULL;
    while (cur != NULL) {
        mock_transfer *next = cur->next;
        if ((mock_handle *)cur->pub.dev_handle == handle) {
            if (prev == NULL) {
                *head = next;
            } else {
                prev->next = next;
            }
            if (*tail == cur) {
                *tail = prev;
            }
            complete_locked(cur, LIBUSB_TRANSFER_NO_DEVICE, 0);
        } else {
            prev = cur;
        }
        cur = next;
    }
}

/* Completes ALL pending transfers with NO_DEVICE (device reset). */
static void fail_all_pending(void)
{
    mock_transfer *t;
    while (g_ctx.out_head != NULL) {
        t = g_ctx.out_head;
        g_ctx.out_head = t->next;
        complete_locked(t, LIBUSB_TRANSFER_NO_DEVICE, 0);
    }
    g_ctx.out_tail = NULL;
    while (g_ctx.in_head != NULL) {
        t = g_ctx.in_head;
        g_ctx.in_head = t->next;
        complete_locked(t, LIBUSB_TRANSFER_NO_DEVICE, 0);
    }
    g_ctx.in_tail = NULL;
}

/* --- sample-clock worker ------------------------------------------------- */

/* Fill the DAC FIFO from the head OUT transfer(s), completing each drained one. */
static void feed_locked(void)
{
    int pushed = 0;                              /* frames accepted this batch (for logging) */
    while (g_ctx.out_head != NULL) {
        mock_transfer *t = g_ctx.out_head;
        int len = t->pub.length;
        unsigned char *buf = t->pub.buffer;
        while (len - t->progress >= FRAME_BYTES && qa403_dac_free_frames(&g_ctx.dev) > 0) {
            int32_t s0, s1;
            memcpy(&s0, buf + t->progress, 4);
            memcpy(&s1, buf + t->progress + 4, 4);
            qa403_dac_push(&g_ctx.dev, s0, s1);
            t->progress += FRAME_BYTES;
            pushed++;
        }
        if (len - t->progress < FRAME_BYTES) {   /* frame-aligned: fully consumed */
            g_ctx.out_head = t->next;
            if (g_ctx.out_head == NULL) {
                g_ctx.out_tail = NULL;
            }
            complete_locked(t, LIBUSB_TRANSFER_COMPLETED, len);
        } else {
            break;                               /* DAC full: resume next tick */
        }
    }
    if (pushed > 0) {
        qa403_log_rx(&g_ctx.dev, pushed);        /* one check per feed batch, not per sample */
    }
}

/* Complete head IN transfer(s) whose length is covered by the ADC FIFO. */
static void deliver_locked(void)
{
    while (g_ctx.in_head != NULL) {
        mock_transfer *t = g_ctx.in_head;
        int len = t->pub.length;
        int need = len / FRAME_BYTES;
        if (need == 0 || qa403_adc_avail_frames(&g_ctx.dev) >= need) {
            unsigned char *buf = t->pub.buffer;
            int i;
            for (i = 0; i < need; i++) {
                int32_t l, r;
                qa403_adc_pop(&g_ctx.dev, &l, &r);
                memcpy(buf + i * FRAME_BYTES,     &l, 4);
                memcpy(buf + i * FRAME_BYTES + 4, &r, 4);
            }
            g_ctx.in_head = t->next;
            if (g_ctx.in_head == NULL) {
                g_ctx.in_tail = NULL;
            }
            complete_locked(t, LIBUSB_TRANSFER_COMPLETED, need * FRAME_BYTES);
            qa403_log_tx(&g_ctx.dev, need);      /* per delivered transfer, not per sample */
        } else {
            break;                               /* not enough ADC yet */
        }
    }
}

static void worker_tick(double elapsed_sec)     /* lock held */
{
    feed_locked();
    if (g_ctx.dev.started) {
        qa403_stream(&g_ctx.dev, elapsed_sec);
        feed_locked();                       /* space freed -> accept more OUT */
        /* Advance in ring-safe slices, feeding between them.  When the app
         * window is fully hidden, Windows 11 revokes the process's 1 ms timers
         * and this tick coarsens to ~15.6 ms - at
         * 192 kHz that is ~3000 frames against a 1024-frame DAC ring, so a
         * single qa403_stream() call padded ~2000 frames of SILENCE per tick
         * while the data sat in pending OUT transfers (the hidden-window
         * "meander").  Real hardware DMA-feeds its queue continuously from
         * pending URBs; slicing at 2 ms (< the ring even at 384 kHz input
         * rates) emulates exactly that. */
        /*double remaining = elapsed_sec;
        while (remaining > 0.0) {
            double s = remaining > 0.002 ? 0.002 : remaining;
            qa403_stream(&g_ctx.dev, s);
            feed_locked();                       // space freed -> accept more OUT/
            remaining -= s;
        }*/
    }
    deliver_locked();
}

/* Worker wait strategy - TWO alternatives, both keeping the sample clock fine-
 * grained when the app is backgrounded (both proven to remove the periodic
 * loopback discontinuities on the bench):
 *   OPTION 1: plain Sleep(1), made reliable by ignore_timer_throttling() above.
 *   OPTION 2: a high-resolution waitable timer - immune to background timer
 *             coarsening BY CONSTRUCTION (it does not depend on the process timer
 *             resolution at all), so it holds even if a future Windows build stops
 *             honouring the throttling opt-out.
 * Flip MOCK_WORKER_HIRES_TIMER to 0 to roll back to OPTION 1 alone.  Option 1's
 * opt-out stays active either way - the two are complementary, not exclusive. */
#define MOCK_WORKER_HIRES_TIMER 1

/* CREATE_WAITABLE_TIMER_HIGH_RESOLUTION (Windows 10 1803+) postdates the VS2015
 * SDK; CreateWaitableTimerExW itself is present since Win7.  Define the flag if
 * the SDK lacks it. */
#ifndef CREATE_WAITABLE_TIMER_HIGH_RESOLUTION
#define CREATE_WAITABLE_TIMER_HIGH_RESOLUTION 0x00000002
#endif

/* A 1 ms periodic high-resolution waitable timer, or NULL when unsupported (older
 * Windows) so the worker falls back to Sleep(1). */
static HANDLE create_worker_timer(void)
{
    HANDLE timer = CreateWaitableTimerExW(NULL, NULL,
        CREATE_WAITABLE_TIMER_HIGH_RESOLUTION, TIMER_ALL_ACCESS);
    if (timer != NULL) {
        LARGE_INTEGER due;
        due.QuadPart = -10000;          /* first fire in 1 ms (100 ns units, relative) */
        SetWaitableTimer(timer, &due, 1 /* ms period */, NULL, NULL, FALSE);
    }
    return timer;
}

static DWORD WINAPI worker_proc(LPVOID param)
{
    LARGE_INTEGER now;
    HANDLE timer = NULL;
    (void)param;
#if MOCK_WORKER_HIRES_TIMER
    timer = create_worker_timer();      /* NULL on older Windows -> Sleep(1) fallback */
#endif
    QueryPerformanceCounter(&g_ctx.qpc_last);
    while (!g_ctx.worker_stop) {
        double elapsed;
        if (timer != NULL) {
            WaitForSingleObject(timer, 1000);   /* ~1 ms tick; 1 s guard keeps stop prompt */
        } else {
            Sleep(1);
        }
        QueryPerformanceCounter(&now);
        elapsed = (double)(now.QuadPart - g_ctx.qpc_last.QuadPart)
                / (double)g_ctx.qpc_freq.QuadPart;
        g_ctx.qpc_last = now;
        EnterCriticalSection(&g_ctx.lock);
        worker_tick(elapsed);
        LeaveCriticalSection(&g_ctx.lock);
    }
    if (timer != NULL) {
        CloseHandle(timer);
    }
    return 0;
}

/* --- enumeration / lifecycle --------------------------------------------- */

MOCK_API int LIBUSB_CALL libusb_init(libusb_context **ctx)
{
    if (InterlockedIncrement(&g_init_count) == 1) {
        InitializeCriticalSection(&g_ctx.lock);
        InitializeConditionVariable(&g_ctx.cv);
        QueryPerformanceFrequency(&g_ctx.qpc_freq);
        g_ctx.out_head = g_ctx.out_tail = NULL;
        g_ctx.in_head = g_ctx.in_tail = NULL;
        g_ctx.done_head = g_ctx.done_tail = NULL;
        g_ctx.claimed_by = NULL;
        g_ctx.worker_stop = 0;
        if (qa403_init(&g_ctx.dev) != 0) {
            DeleteCriticalSection(&g_ctx.lock);
            InterlockedDecrement(&g_init_count);
            return LIBUSB_ERROR_NO_MEM;
        }
        ignore_timer_throttling();   /* keep the sample clock fine-grained when backgrounded */
        timeBeginPeriod(1);
        g_ctx.worker = CreateThread(NULL, 0, worker_proc, NULL, 0, NULL);
    }
    if (ctx != NULL) {
        *ctx = (libusb_context *)&g_ctx;
    }
    return LIBUSB_SUCCESS;
}

MOCK_API void LIBUSB_CALL libusb_exit(libusb_context *ctx)
{
    (void)ctx;
    if (InterlockedDecrement(&g_init_count) == 0) {
        InterlockedExchange(&g_ctx.worker_stop, 1);
        WakeAllConditionVariable(&g_ctx.cv);
        if (g_ctx.worker != NULL) {
            WaitForSingleObject(g_ctx.worker, INFINITE);
            CloseHandle(g_ctx.worker);
            g_ctx.worker = NULL;
        }
        timeEndPeriod(1);
        qa403_free(&g_ctx.dev);
        DeleteCriticalSection(&g_ctx.lock);
    }
}

MOCK_API const char * LIBUSB_CALL libusb_error_name(int errcode)
{
    switch (errcode) {
    case LIBUSB_SUCCESS:              return "LIBUSB_SUCCESS";
    case LIBUSB_ERROR_IO:             return "LIBUSB_ERROR_IO";
    case LIBUSB_ERROR_INVALID_PARAM:  return "LIBUSB_ERROR_INVALID_PARAM";
    case LIBUSB_ERROR_ACCESS:         return "LIBUSB_ERROR_ACCESS";
    case LIBUSB_ERROR_NO_DEVICE:      return "LIBUSB_ERROR_NO_DEVICE";
    case LIBUSB_ERROR_NOT_FOUND:      return "LIBUSB_ERROR_NOT_FOUND";
    case LIBUSB_ERROR_BUSY:           return "LIBUSB_ERROR_BUSY";
    case LIBUSB_ERROR_TIMEOUT:        return "LIBUSB_ERROR_TIMEOUT";
    case LIBUSB_ERROR_INTERRUPTED:    return "LIBUSB_ERROR_INTERRUPTED";
    case LIBUSB_ERROR_NO_MEM:         return "LIBUSB_ERROR_NO_MEM";
    case LIBUSB_ERROR_NOT_SUPPORTED:  return "LIBUSB_ERROR_NOT_SUPPORTED";
    case LIBUSB_ERROR_OTHER:          return "LIBUSB_ERROR_OTHER";
    default:                          return "LIBUSB_ERROR_OTHER";
    }
}

MOCK_API intptr_t LIBUSB_CALL libusb_get_device_list(libusb_context *ctx, libusb_device ***list)
{
    libusb_device **arr;
    (void)ctx;
    if (list == NULL) {
        return LIBUSB_ERROR_INVALID_PARAM;
    }
    arr = (libusb_device **)malloc(2 * sizeof(libusb_device *));
    if (arr == NULL) {
        return LIBUSB_ERROR_NO_MEM;
    }
    arr[0] = (libusb_device *)&g_device;
    arr[1] = NULL;
    *list = arr;
    return 1;
}

MOCK_API void LIBUSB_CALL libusb_free_device_list(libusb_device **list, int unref_devices)
{
    (void)unref_devices;             /* the device is a static object */
    free(list);
}

MOCK_API int LIBUSB_CALL libusb_get_device_descriptor(libusb_device *dev,
        struct libusb_device_descriptor *desc)
{
    (void)dev;
    if (desc == NULL) {
        return LIBUSB_ERROR_INVALID_PARAM;
    }
    desc->bLength = 18;
    desc->bDescriptorType = 1;
    desc->bcdUSB = 0x0200;
    desc->bDeviceClass = 0xFF;
    desc->bDeviceSubClass = 0;
    desc->bDeviceProtocol = 0;
    desc->bMaxPacketSize0 = 64;
    desc->idVendor = QA_VID;
    desc->idProduct = QA_PID_QA403;
    desc->bcdDevice = 0x0100;
    desc->iManufacturer = 0;
    desc->iProduct = 0;
    desc->iSerialNumber = 0;
    desc->bNumConfigurations = 1;
    return LIBUSB_SUCCESS;
}

MOCK_API uint8_t LIBUSB_CALL libusb_get_bus_number(libusb_device *dev)
{
    (void)dev;
    return QA_BUS_NUMBER;
}

MOCK_API uint8_t LIBUSB_CALL libusb_get_device_address(libusb_device *dev)
{
    (void)dev;
    return QA_DEV_ADDRESS;
}

/* --- handles ------------------------------------------------------------- */

MOCK_API int LIBUSB_CALL libusb_open(libusb_device *dev, libusb_device_handle **handle)
{
    mock_handle *h;
    (void)dev;
    if (handle == NULL) {
        return LIBUSB_ERROR_INVALID_PARAM;
    }
    h = (mock_handle *)malloc(sizeof(mock_handle));
    if (h == NULL) {
        return LIBUSB_ERROR_NO_MEM;
    }
    h->reserved = 0;
    *handle = (libusb_device_handle *)h;
    return LIBUSB_SUCCESS;
}

MOCK_API void LIBUSB_CALL libusb_close(libusb_device_handle *handle)
{
    mock_handle *h = (mock_handle *)handle;
    if (h == NULL) {
        return;
    }
    EnterCriticalSection(&g_ctx.lock);
    if (g_ctx.claimed_by == h) {
        g_ctx.claimed_by = NULL;
    }
    fail_pending_for_handle(&g_ctx.out_head, &g_ctx.out_tail, h);
    fail_pending_for_handle(&g_ctx.in_head, &g_ctx.in_tail, h);
    LeaveCriticalSection(&g_ctx.lock);
    free(h);
}

MOCK_API int LIBUSB_CALL libusb_reset_device(libusb_device_handle *handle)
{
    if (handle == NULL) {
        return LIBUSB_ERROR_INVALID_PARAM;
    }
    EnterCriticalSection(&g_ctx.lock);
    qa403_reset(&g_ctx.dev);
    fail_all_pending();
    qa403_logf("[QA403] libusb_reset_device\n");
    LeaveCriticalSection(&g_ctx.lock);
    return LIBUSB_SUCCESS;
}

/* The device has exactly ONE configuration (bNumConfigurations = 1 in the
 * descriptor), and an opened device is always in it.  Real libusb answers from
 * the OS, which configures the device at enumeration on Windows and Linux;
 * macOS may leave it unconfigured, which is the whole reason the Java side
 * asks before claiming.  Here it is always configured, so the caller's
 * set_configuration branch is simply never taken. */
MOCK_API int LIBUSB_CALL libusb_get_configuration(libusb_device_handle *handle, int *configuration)
{
    if (handle == NULL || configuration == NULL) {
        return LIBUSB_ERROR_INVALID_PARAM;
    }
    *configuration = QA403_CONFIGURATION;
    qa403_logf("[QA403] libusb_get_configuration -> %d\n", QA403_CONFIGURATION);
    return LIBUSB_SUCCESS;
}

/* Selecting the only configuration is a no-op that succeeds; selecting any
 * other one cannot be satisfied, and real libusb answers ERROR_NOT_FOUND for a
 * configuration the device does not have.  The UNCONFIGURED state - which real
 * libusb spells as -1, and which 0 is also commonly used for - is refused for
 * the same reason: nothing in this stack asks for it, and quietly accepting it
 * would leave the mock claiming a state it does not model. */
MOCK_API int LIBUSB_CALL libusb_set_configuration(libusb_device_handle *handle, int configuration)
{
    if (handle == NULL) {
        return LIBUSB_ERROR_INVALID_PARAM;
    }
    if (configuration != QA403_CONFIGURATION) {
        qa403_logf("[QA403] libusb_set_configuration(%d) -> NOT_FOUND\n", configuration);
        return LIBUSB_ERROR_NOT_FOUND;
    }
    qa403_logf("[QA403] libusb_set_configuration(%d) -> OK\n", configuration);
    return LIBUSB_SUCCESS;
}

MOCK_API int LIBUSB_CALL libusb_claim_interface(libusb_device_handle *handle, int interface_number)
{
    mock_handle *h = (mock_handle *)handle;
    int rc;
    if (interface_number != 0) {
        qa403_logf("[QA403] libusb_claim_interface(%d) -> NOT_FOUND\n", interface_number);
        return LIBUSB_ERROR_NOT_FOUND;
    }
    EnterCriticalSection(&g_ctx.lock);
    if (g_ctx.claimed_by == NULL || g_ctx.claimed_by == h) {
        g_ctx.claimed_by = h;                /* idempotent for the same handle */
        rc = LIBUSB_SUCCESS;
    } else {
        rc = LIBUSB_ERROR_BUSY;
    }
    qa403_logf("[QA403] libusb_claim_interface(0) -> %s\n",
               rc == LIBUSB_SUCCESS ? "OK" : "BUSY");
    LeaveCriticalSection(&g_ctx.lock);
    return rc;
}

MOCK_API int LIBUSB_CALL libusb_release_interface(libusb_device_handle *handle, int interface_number)
{
    mock_handle *h = (mock_handle *)handle;
    int rc;
    EnterCriticalSection(&g_ctx.lock);
    if (interface_number == 0 && g_ctx.claimed_by == h) {
        g_ctx.claimed_by = NULL;
        rc = LIBUSB_SUCCESS;
    } else {
        rc = LIBUSB_ERROR_NOT_FOUND;
    }
    LeaveCriticalSection(&g_ctx.lock);
    return rc;
}

/* --- synchronous bulk (registers + calibration, doc section 4/6) --------- */

MOCK_API int LIBUSB_CALL libusb_bulk_transfer(libusb_device_handle *handle, unsigned char endpoint,
        unsigned char *data, int length, int *transferred, unsigned int timeout)
{
    int rc = LIBUSB_SUCCESS;
    (void)handle;
    (void)timeout;
    if (data == NULL) {
        return LIBUSB_ERROR_INVALID_PARAM;
    }
    EnterCriticalSection(&g_ctx.lock);
    if (endpoint == EP_REG_OUT) {
        if (length != REG_FRAME_BYTES) {
            rc = LIBUSB_ERROR_INVALID_PARAM;
        } else {
            unsigned addr = data[0];
            uint32_t value = ((uint32_t)data[1] << 24) | ((uint32_t)data[2] << 16)
                           | ((uint32_t)data[3] << 8) | (uint32_t)data[4];
            if (addr & 0x80) {                        /* READ REQUEST (doc section 4) */
                uint32_t v = qa403_reg_read(&g_ctx.dev, addr & 0x7F);
                qa403_reply_push(&g_ctx.dev, v);
            } else {                                  /* WRITE (big-endian value) */
                qa403_reg_write(&g_ctx.dev, addr, value);
            }
            if (transferred != NULL) {
                *transferred = REG_FRAME_BYTES;
            }
        }
    } else if (endpoint == EP_REG_IN) {
        uint32_t v;
        if (length < REG_REPLY_BYTES) {
            rc = LIBUSB_ERROR_INVALID_PARAM;
        } else if (qa403_reply_pop(&g_ctx.dev, &v)) {
            data[0] = (unsigned char)(v >> 24);       /* reply is big-endian (doc s.4) */
            data[1] = (unsigned char)(v >> 16);
            data[2] = (unsigned char)(v >> 8);
            data[3] = (unsigned char)v;
            if (transferred != NULL) {
                *transferred = REG_REPLY_BYTES;
            }
        } else {
            rc = LIBUSB_ERROR_TIMEOUT;                /* no reply queued */
        }
    } else {
        rc = LIBUSB_ERROR_NOT_SUPPORTED;             /* audio streams async only */
    }
    LeaveCriticalSection(&g_ctx.lock);
    return rc;
}

/* --- asynchronous transfers (audio, doc section 5) ----------------------- */

MOCK_API struct libusb_transfer * LIBUSB_CALL libusb_alloc_transfer(int iso_packets)
{
    mock_transfer *m;
    if (iso_packets != 0) {
        return NULL;                                 /* iso unsupported */
    }
    m = (mock_transfer *)calloc(1, sizeof(mock_transfer));
    if (m == NULL) {
        return NULL;
    }
    return &m->pub;                                  /* pub is first: &pub == block */
}

MOCK_API void LIBUSB_CALL libusb_free_transfer(struct libusb_transfer *transfer)
{
    if (transfer == NULL) {
        return;                                      /* NULL-safe */
    }
    free(transfer);                                  /* == the mock_transfer block */
}

MOCK_API int LIBUSB_CALL libusb_submit_transfer(struct libusb_transfer *transfer)
{
    mock_transfer *m = (mock_transfer *)transfer;
    unsigned char ep = transfer->endpoint;
    int rc = LIBUSB_SUCCESS;
    EnterCriticalSection(&g_ctx.lock);
    m->progress = 0;
    if (ep == EP_AUDIO_OUT) {
        enqueue(&g_ctx.out_head, &g_ctx.out_tail, m);
    } else if (ep == EP_AUDIO_IN) {
        enqueue(&g_ctx.in_head, &g_ctx.in_tail, m);
    } else {
        rc = LIBUSB_ERROR_NOT_SUPPORTED;
    }
    LeaveCriticalSection(&g_ctx.lock);
    return rc;
}

MOCK_API int LIBUSB_CALL libusb_cancel_transfer(struct libusb_transfer *transfer)
{
    mock_transfer *m = (mock_transfer *)transfer;
    int rc;
    EnterCriticalSection(&g_ctx.lock);
    if (remove_pending(&g_ctx.out_head, &g_ctx.out_tail, m)
            || remove_pending(&g_ctx.in_head, &g_ctx.in_tail, m)) {
        complete_locked(m, LIBUSB_TRANSFER_CANCELLED, 0);
        rc = LIBUSB_SUCCESS;
    } else {
        rc = LIBUSB_ERROR_NOT_FOUND;                 /* already completed: benign */
    }
    LeaveCriticalSection(&g_ctx.lock);
    return rc;
}

MOCK_API int LIBUSB_CALL libusb_handle_events_timeout_completed(libusb_context *ctx,
        struct timeval *tv, int *completed)
{
    mock_transfer *local, *t;
    (void)ctx;
    if (completed != NULL && *completed != 0) {
        return LIBUSB_SUCCESS;                        /* libusb semantics */
    }
    EnterCriticalSection(&g_ctx.lock);
    local = g_ctx.done_head;
    g_ctx.done_head = g_ctx.done_tail = NULL;
    if (local == NULL) {                              /* nothing ready: wait, drain once more */
        DWORD ms = (tv != NULL) ? (DWORD)(tv->tv_sec * 1000 + tv->tv_usec / 1000) : 0;
        SleepConditionVariableCS(&g_ctx.cv, &g_ctx.lock, ms);
        local = g_ctx.done_head;
        g_ctx.done_head = g_ctx.done_tail = NULL;
    }
    LeaveCriticalSection(&g_ctx.lock);
    /* Invoke callbacks OUTSIDE the lock; capture next FIRST - the callback frees
     * the transfer (libusb_free_transfer) and may re-submit. */
    t = local;
    while (t != NULL) {
        mock_transfer *next = t->next;
        if (t->pub.callback != NULL) {
            t->pub.callback(&t->pub);
        }
        t = next;
    }
    return LIBUSB_SUCCESS;
}
