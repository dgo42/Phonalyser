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

package org.edgo.audio.measure.net.server;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.edgo.audio.measure.net.proto.NetProto;

import lombok.Getter;

/**
 * The uploaded files of spec 3: "files live in server RAM, owned by nobody until
 * referenced by {@code gen.playFile}, and are dropped when the referencing
 * connection closes or on {@code DELETE /files/{id}}".
 *
 * <p>RAM and not a temporary directory, deliberately - a bench server that
 * crashes must not leave the operator's recordings on its disk, and the upload
 * cap (spec 3: 50 MB, enforced by {@link HttpFront} before a byte is kept) is
 * what makes holding them in memory safe.
 *
 * <p><b>And a bound on the WHOLE store</b> ({@link #getMaxTotalBytes()}).  Spec 3
 * caps ONE upload and gives the store itself no limit, but an upload nobody ever
 * plays is dropped by nothing except {@code DELETE /files/{id}} - and the plane
 * has no authentication (spec 3), so any LAN peer could put 50 MB after 50 MB
 * until the server's heap was gone.  Above the bound {@link #put(byte[])}
 * refuses, and the operator is told which limit was reached.
 *
 * <p><b>Ownership is claimed, not given.</b>  An upload arrives over HTTP, which
 * carries no session at all, so a freshly uploaded file belongs to nobody and
 * survives until somebody deletes it.  The moment a connection plays it
 * ({@code gen.playFile}) that connection OWNS it, and
 * {@link #releaseAll(Object)} on the session teardown drops it - otherwise a
 * client that vanished mid-measurement would leave 50 MB of the operator's audio
 * in the server's heap for as long as the process lives, with no handle left
 * anywhere to delete it by.  Two connections playing the same file both own it;
 * it goes when the last of them does.
 *
 * <p>One instance per server, handed to whoever needs it: the HTTP front puts
 * files in, and the generator session takes them out when a client plays one.
 * The maps are concurrent because those two arrive on different threads - an
 * upload on an HTTP worker, a {@code gen.playFile} on a session's own thread.
 *
 * <p>The handle is a string ({@code "f-1"}), not a wire integer, so it needs no
 * range-safe allocator: nothing in spec 5 carries a file id in a u8 or u16.
 */
public final class FileStore {

    /** Handle prefix of spec 3's {@code {"fileId":"f-1"}}. */
    private static final String ID_PREFIX = "f-";

    /** How many bytes of uploads the whole store may hold at once: four times
     *  the per-upload cap of spec 3 - room for the handful of files a bench
     *  plays through the generator lane, and a ceiling an anonymous uploader
     *  cannot walk past. */
    private static final long DEFAULT_MAX_TOTAL_BYTES = 4L * NetProto.MAX_UPLOAD_BYTES;

    /** The bound this store enforces - quoted by {@link HttpFront} in the
     *  refusal it sends, so the operator is told which limit was reached. */
    @Getter
    private final long maxTotalBytes;

    private final Map<String, byte[]> files = new ConcurrentHashMap<>();
    /** The {@code Content-Type} each upload declared, for the handles that
     *  carried one - beside the bytes, dropped with them. */
    private final Map<String, String> types = new ConcurrentHashMap<>();
    /** Who referenced which file.  Keyed by handle, valued by the owners that
     *  claimed it - a copy-on-write list because a claim is rare and a teardown
     *  walks every entry. */
    private final Map<String, List<Object>> owners = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong(1);
    /** How much the store holds right now, against {@link #maxTotalBytes}.
     *  Reserved BEFORE the file goes in and given back when it goes out, so two
     *  uploads racing on different HTTP threads cannot both pass the bound. */
    private final AtomicLong heldBytes = new AtomicLong();

    public FileStore() {
        this(DEFAULT_MAX_TOTAL_BYTES);
    }

    /** The same store with a bound of the caller's choosing - the one seam a
     *  test needs to reach the limit without allocating hundreds of megabytes. */
    FileStore(long maxTotalBytes) {
        this.maxTotalBytes = maxTotalBytes;
    }

    /** Keeps {@code content} and answers the handle spec 3 returns.  The file is
     *  nobody's until a {@code gen.playFile} claims it.
     *
     *  @return null when the store is full ({@link #getMaxTotalBytes()}) - the
     *          caller answers {@code FILE_TOO_LARGE} */
    public String put(byte[] content) {
        return put(content, null);
    }

    /** The same, keeping the {@code Content-Type} the upload declared (spec 3,
     *  v1.1) so the generator can stage the file by what it IS rather than by
     *  what its first bytes resemble.  Null when the client sent none. */
    public String put(byte[] content, String mimeType) {
        if (heldBytes.addAndGet(content.length) > maxTotalBytes) {
            heldBytes.addAndGet(-content.length);
            return null;
        }
        String fileId = ID_PREFIX + nextId.getAndIncrement();
        files.put(fileId, content);
        if (mimeType != null && !mimeType.isBlank()) {
            types.put(fileId, mimeType);
        }
        return fileId;
    }

    /** The MIME type declared for {@code fileId}, or null when the upload named
     *  none - then the caller falls back to sniffing the content. */
    public String typeOf(String fileId) {
        return fileId == null ? null : types.get(fileId);
    }

    /** The bytes behind a handle, or null when nothing was uploaded under it (or
     *  it was already dropped) - the caller answers {@code NO_SUCH_FILE}. */
    public byte[] get(String fileId) {
        return fileId == null ? null : files.get(fileId);
    }

    /**
     * Records that {@code owner} - one connection's generator - now references
     * {@code fileId}, so {@link #releaseAll(Object)} will drop it when that
     * connection goes.  Claiming the same file twice changes nothing.
     *
     * @return false when the handle is unknown, so the caller can answer
     *         {@code NO_SUCH_FILE} instead of owning a file that is not there
     */
    public boolean claim(String fileId, Object owner) {
        if (!files.containsKey(fileId)) {
            return false;
        }
        owners.compute(fileId, (id, held) -> {
            List<Object> next = held == null ? new ArrayList<>() : new ArrayList<>(held);
            if (!next.contains(owner)) {
                next.add(owner);
            }
            return next;
        });
        return true;
    }

    /** Drops every file {@code owner} claimed and nobody else still holds - the
     *  file half of the session teardown (spec 3, spec 4.1).
     *
     *  @return how many files were dropped */
    public int releaseAll(Object owner) {
        int dropped = 0;
        for (Iterator<Map.Entry<String, List<Object>>> it =
                owners.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, List<Object>> entry = it.next();
            List<Object> held = new ArrayList<>(entry.getValue());
            if (!held.remove(owner)) {
                continue;
            }
            if (held.isEmpty()) {
                it.remove();
                if (drop(entry.getKey())) {
                    dropped++;
                }
            } else {
                entry.setValue(held);
            }
        }
        return dropped;
    }

    /** Drops a file whoever owns it - {@code DELETE /files/{id}}, which spec 3
     *  gives no owner check.  False when the handle was unknown. */
    public boolean remove(String fileId) {
        if (fileId == null || !drop(fileId)) {
            return false;
        }
        owners.remove(fileId);
        return true;
    }

    /** Takes the bytes out and gives their share of {@link #MAX_TOTAL_BYTES}
     *  back - the one place a file leaves the store, so the accounting cannot
     *  drift away from what is actually held. */
    private boolean drop(String fileId) {
        byte[] gone = files.remove(fileId);
        if (gone == null) {
            return false;
        }
        // The declared type is part of the entry, so it leaves with it - a handle
        // is reused by nobody, but a map that only ever grew would be a leak.
        types.remove(fileId);
        heldBytes.addAndGet(-gone.length);
        return true;
    }
}
