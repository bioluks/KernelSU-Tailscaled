package io.github.bioluks.tailscaled;

public final class TransferProgressTest {
    private static void equal(long expected, long actual) {
        if (expected != actual) throw new AssertionError("Expected " + expected + ", got " + actual);
    }

    private static void rate(double expected, TransferProgress progress) {
        if (Math.abs(expected - progress.bytesPerSecond()) > 0.001)
            throw new AssertionError("Expected rate " + expected + ", got " + progress.bytesPerSecond());
    }

    public static void main(String[] args) {
        TransferProgress first = new TransferProgress("1/2 · first", 2000, 1000);
        first.addBytes(1000);
        first.sample(1500);
        equal(1000, first.sentBytes());
        equal(2000, first.totalBytes());
        equal(50, first.percent());
        rate(2000, first);

        // A sample must age out the rate even when the streaming thread is blocked.
        first.sample(2000);
        rate(0, first);
        first.addBytes(500);
        first.sample(4000);
        rate(250, first);
        first.addBytes(500);
        first.finishInput();
        first.sample(4500);
        equal(100, first.percent());
        if (!first.isFinalizing()) throw new AssertionError("EOF must wait for CLI completion");
        first.sample(5000);
        rate(0, first);

        TransferProgress second = new TransferProgress("2/2 · second", 4000, 5000);
        second.sample(5500);
        equal(0, second.sentBytes());
        equal(0, second.percent());
        rate(0, second);
        if (second.isFinalizing()) throw new AssertionError("New file inherited EOF");
        second.addBytes(1000);
        second.sample(6000);
        equal(25, second.percent());
        rate(2000, second);

        TransferProgress unknown = new TransferProgress("unknown", -1, 0);
        unknown.addBytes(8192);
        unknown.finishInput();
        unknown.sample(500);
        equal(8192, unknown.sentBytes());
        equal(-1, unknown.totalBytes());
        equal(0, unknown.percent());

        TransferProgress empty = new TransferProgress("empty", 0, 0);
        empty.sample(500);
        equal(0, empty.totalBytes());
        equal(0, empty.percent());
        rate(0, empty);
        if (empty.isFinalizing()) throw new AssertionError("Metadata is not EOF");
        empty.finishInput();
        empty.sample(1000);
        equal(0, empty.totalBytes());
        equal(100, empty.percent());

        TransferProgress wrongZero = new TransferProgress("wrong zero", 0, 0);
        wrongZero.addBytes(1);
        wrongZero.sample(500);
        equal(-1, wrongZero.totalBytes());
        TransferProgress truncated = new TransferProgress("shorter than metadata", 100, 0);
        truncated.addBytes(50);
        truncated.finishInput();
        truncated.sample(500);
        equal(-1, truncated.totalBytes());

        TransferProgress shortFile = new TransferProgress("short", 64, 1000);
        shortFile.addBytes(64);
        shortFile.finishInput();
        shortFile.sample(1000);
        equal(64, shortFile.sentBytes());
        rate(0, shortFile); // No division by zero or invented instantaneous rate.
        shortFile.sample(1500);
        rate(128, shortFile);
        shortFile.sample(2000);
        rate(0, shortFile);

        TransferProgress large = new TransferProgress("large", 12000000000L, 0);
        for (int index = 0; index < 3; index++) large.addBytes(2000000000);
        large.sample(500);
        equal(6000000000L, large.sentBytes());
        equal(50, large.percent());
        rate(12000000000.0, large);
    }
}
