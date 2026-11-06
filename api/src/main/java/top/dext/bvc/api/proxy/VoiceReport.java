package top.dext.bvc.api.proxy;

import java.util.UUID;

/**
 * A voice report filed with {@code /vreport} or {@link ReportService#report}.
 */
public interface VoiceReport {

    long id();

    UUID reporter();

    String reporterName();

    UUID target();

    String targetName();

    /** Sub-server the reporter was on. */
    String server();

    String reason();

    long createdAt();

    /** {@code COLLECTING} (evidence still being recorded), {@code OPEN}, {@code ACCEPTED} or {@code REJECTED}. */
    String status();
}
