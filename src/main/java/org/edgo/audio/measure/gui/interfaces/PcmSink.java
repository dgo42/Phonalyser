package org.edgo.audio.measure.gui.interfaces;

import java.io.IOException;

/** Tiny common surface for the format-specific writers (WAV / FLAC /
 *  AIFF) used by the generator's "Save to…" and the oscilloscope's
 *  capture-save paths.  Lets a switch-on-format helper produce a single
 *  closeable sink without leaking the concrete writer types. */
public interface PcmSink extends AutoCloseable {
    void writeRaw(byte[] buf, int length) throws IOException;
    @Override void close() throws IOException;
}
