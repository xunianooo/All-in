package com.gaeul.allin;

import java.io.IOException;

/**
 * Send-stage evidence for the single Raccoon refresh POST.
 *
 * <p>The refresh token is one-use material: the vendor may rotate it the moment it processes the
 * request. So when the POST produces no usable response, a caller has to tell two very different
 * things apart:
 *
 * <ul>
 *   <li><b>Provably not executed</b> - the link was never established, so not one request byte
 *       could have reached the vendor. The same refresh token is still current and may be sent
 *       again (the gateway may release its one-use fence).</li>
 *   <li><b>Unknown</b> - the request may already have been executed, or the evidence is missing or
 *       contradictory. The old token may be spent, so it must never be replayed.</li>
 * </ul>
 *
 * <p>Classification is conservative by construction and is derived from declared stage/byte
 * evidence only, never from the exception type, class or message:
 *
 * <pre>
 * NOT_EXECUTED  iff  stage is NOT_STARTED or NOT_CONNECTED  and  requestBytesWritten == 0
 * UNKNOWN       otherwise
 * </pre>
 *
 * <p>The unknown side explicitly includes a failed write that acknowledged zero bytes (the kernel
 * may already hold the bytes), any failure after a request byte was written, every response-stage
 * failure, a transport error that declares nothing, and a negative (unknown) byte count. This is
 * the same conservative direction as {@code AntigravityAuth.SendFailure}, and it is stricter in
 * one place: an undeclared failure here classifies as UNKNOWN, because replaying a one-use refresh
 * token is irreversible while a re-login is merely inconvenient.
 *
 * <p>{@code NOT_STARTED} covers the gateway's own refusal to send (the one-use fence could not be
 * made durable): nothing left the process either, so that case is retryable as well, and no fence
 * entry was created.
 *
 * <p>Nothing here may carry a credential: messages and {@link Failure#diagnostic()} are built only
 * from stage names and byte counts.
 */
public final class RaccoonRefreshSend {
    private RaccoonRefreshSend() {}

    /** How far the refresh POST got before it failed. */
    public enum Stage {
        /** Nothing was attempted: no socket was opened and the caller refuses to send. */
        NOT_STARTED,
        /** DNS/TCP/proxy/TLS never completed, so not one request byte was written. */
        NOT_CONNECTED,
        /** A write to the request stream was attempted, whether it acknowledged bytes or not. */
        WRITING,
        /** The complete request was written and the status line is still outstanding. */
        WRITTEN,
        /** Reading the status line/headers; no usable status yet. */
        READING_STATUS,
        /** A status was read, but the body is incomplete, unreadable or over the size limit. */
        READING_BODY,
        /** The transport failed without declaring a stage. */
        UNSPECIFIED
    }

    public enum Execution { NOT_EXECUTED, UNKNOWN }

    /** Transport-level refresh failure with declared send-stage evidence. */
    public static final class Failure extends IOException {
        private static final long serialVersionUID = 1L;
        public final Stage stage;
        /** Request bytes the transport could confirm were handed to the socket. */
        public final long requestBytesWritten;
        /** Request bytes the transport intended to write; -1 when unknown. */
        public final long requestBytesTotal;
        private final boolean declared;

        private Failure(Stage stage, long requestBytesWritten, long requestBytesTotal, boolean declared,
                        String message, Throwable cause) {
            super(message, cause);
            this.stage = stage == null ? Stage.UNSPECIFIED : stage;
            this.requestBytesWritten = requestBytesWritten;
            this.requestBytesTotal = requestBytesTotal;
            this.declared = declared;
        }

        public Execution execution() { return executionOf(stage, requestBytesWritten); }

        /** True only when the vendor provably never saw a request byte. */
        public boolean provablyNotExecuted() { return execution() == Execution.NOT_EXECUTED; }

        /** True when the transport declared the stage instead of this layer defaulting it. */
        public boolean declaredEvidence() { return declared; }

        /**
         * Credential-free description of the failure: stage names and byte counts only.
         */
        public String diagnostic() {
            return "Raccoon refresh " + (provablyNotExecuted() ? "was provably not executed"
                    : "may already have been executed")
                    + " (stage=" + stage.name() + ", bytes=" + requestBytesWritten + "/"
                    + requestBytesTotal + (declared ? "" : ", undeclared") + ")";
        }
    }

    /**
     * Strict classifier: a pre-send stage only proves non-execution when the transport also reports
     * that it wrote zero request bytes. Every other combination - including a contradictory one
     * such as NOT_CONNECTED with bytes written - reads as UNKNOWN.
     */
    public static Execution executionOf(Stage stage, long requestBytesWritten) {
        if (stage == Stage.NOT_STARTED || stage == Stage.NOT_CONNECTED)
            return requestBytesWritten == 0 ? Execution.NOT_EXECUTED : Execution.UNKNOWN;
        return Execution.UNKNOWN;
    }

    /** A failure that happened before any request byte could be written. */
    public static Failure notSent(Stage stage, long requestBytesTotal, String message, Throwable cause) {
        if (stage != Stage.NOT_STARTED && stage != Stage.NOT_CONNECTED)
            throw new IllegalArgumentException("notSent requires NOT_STARTED or NOT_CONNECTED");
        return new Failure(stage, 0, requestBytesTotal, true, message, cause);
    }

    /** A failure whose outcome cannot be proven; {@code requestBytesWritten} may be -1 (unknown). */
    public static Failure unknown(Stage stage, long requestBytesWritten, long requestBytesTotal,
                                 String message, Throwable cause) {
        if (stage == Stage.NOT_STARTED || stage == Stage.NOT_CONNECTED)
            throw new IllegalArgumentException("use notSent for a provable pre-send failure");
        return new Failure(stage, requestBytesWritten, requestBytesTotal, true, message, cause);
    }

    /** Fail closed: a transport error without declared evidence is UNKNOWN, never "not sent". */
    public static Failure wrap(IOException transportFailure) {
        if (transportFailure instanceof Failure) return (Failure) transportFailure;
        if (transportFailure == null) throw new IllegalArgumentException("failure required");
        return new Failure(Stage.UNSPECIFIED, -1, -1, false, text(transportFailure), transportFailure);
    }

    /** Fail closed for a transport crash: the outcome is unknown, never "not sent". */
    public static Failure wrapCrash(RuntimeException transportCrash) {
        if (transportCrash == null) throw new IllegalArgumentException("failure required");
        return new Failure(Stage.UNSPECIFIED, -1, -1, false, text(transportCrash), transportCrash);
    }

    /**
     * Keeps the transport's own wording, so callers that surface it to the user (for example the
     * gateway's one-use fence text) still show the same message they did before this layer existed.
     */
    private static String text(Throwable failure) {
        String message = failure == null ? null : failure.getMessage();
        return message == null || message.trim().isEmpty() ? "Raccoon refresh transport failed" : message;
    }

    /**
     * Whether a caught failure proves that the one-use value was never sent. A gateway may release
     * its reservation (release the fence) only when this returns true; every other failure, and
     * every non-send failure, must keep it.
     */
    public static boolean releasesReservation(Throwable failure) {
        return failure instanceof Failure && ((Failure) failure).provablyNotExecuted();
    }
}
