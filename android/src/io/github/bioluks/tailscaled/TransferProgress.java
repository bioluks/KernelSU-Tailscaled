package io.github.bioluks.tailscaled;

/** Counts file bytes accepted by the CLI's stdin, not remote delivery acknowledgements. */
final class TransferProgress {
    final String fileLabel;
    private final long reportedTotal;
    private volatile long writtenBytes;
    private volatile boolean inputComplete;
    private long sampledAt;
    private long previousBytes;
    private long sampledBytes;
    private double bytesPerSecond;
    private boolean finalizing;

    TransferProgress(String fileLabel, long totalBytes, long nowMillis) {
        this.fileLabel = fileLabel;
        reportedTotal = totalBytes;
        sampledAt = nowMillis;
    }

    // Only the streaming thread writes these two fields; sampling never blocks it.
    void addBytes(int count) { writtenBytes += count; }
    void finishInput() { inputComplete = true; }

    // Called only by the notification thread, with monotonic milliseconds.
    void sample(long nowMillis) {
        finalizing = inputComplete;
        sampledBytes = writtenBytes;
        long elapsed = nowMillis - sampledAt;
        if (elapsed > 0) {
            bytesPerSecond = (sampledBytes - previousBytes) * 1000.0 / elapsed;
            previousBytes = sampledBytes;
            sampledAt = nowMillis;
        }
    }

    long sentBytes() { return sampledBytes; }
    double bytesPerSecond() { return bytesPerSecond; }
    boolean isFinalizing() { return finalizing; }

    long totalBytes() {
        // A provider's missing or inconsistent size is never replaced with a guess.
        if (reportedTotal < sampledBytes || (finalizing && reportedTotal != sampledBytes)) return -1;
        return reportedTotal;
    }

    int percent() {
        long total = totalBytes();
        if (total < 0) return 0;
        if (total == 0) return finalizing ? 100 : 0;
        return (int) Math.min(100, sampledBytes * 100.0 / total);
    }
}
