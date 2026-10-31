package com.sanrotech.pos;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Conservative, ordered ESC/POS delivery over Android Bluetooth SPP.
 * Sending all bytes / flush() does not prove the printer physically printed.
 * These timings are a TESTING starting point for Panda PRJ-80BT hardware UAT.
 */
final class BluetoothJobWriter {
    static final int CHUNK_BYTES = 96;
    static final int BETWEEN_CHUNKS_MS = 45;
    static final int AFTER_LAST_BYTE_MS = 1200;

    private BluetoothJobWriter() {}

    static void send(OutputStream out, byte[] bytes) throws IOException, InterruptedException {
        if (out == null || bytes == null || bytes.length == 0)
            throw new IOException("Data Bluetooth kosong.");
        for (int offset = 0; offset < bytes.length; offset += CHUNK_BYTES) {
            int count = Math.min(CHUNK_BYTES, bytes.length - offset);
            out.write(bytes, offset, count);
            out.flush();
            // Limit bursts into the printer's (unverified) receive buffer.
            if (offset + count < bytes.length) Thread.sleep(BETWEEN_CHUNKS_MS);
        }
        // Do not close SPP immediately after flush; the last bytes may still
        // be queued in Bluetooth stack or device. This is NOT physical ACK.
        Thread.sleep(AFTER_LAST_BYTE_MS);
    }
}
