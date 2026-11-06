package top.dext.bvc.api.proxy;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Voice reports, e.g. for a ticket or Discord bridge. Evidence and playback stay in the web panel.
 */
public interface ReportService {

    /**
     * Files a report as if {@code reporter} ran {@code /vreport} on {@code server} (no cooldown).
     * Completes with the report id, or {@code -1} if reports are disabled or the target is the reporter.
     */
    CompletableFuture<Long> report(UUID reporter, String reporterName, UUID target, String targetName, String server,
                                   String reason);

    /** Newest first; {@code status} {@code null} = all. */
    CompletableFuture<List<VoiceReport>> reports(String status, int limit);

    /** Sets {@code ACCEPTED}, {@code REJECTED} or {@code OPEN}. */
    CompletableFuture<Void> setStatus(long id, String status, String operator, String note);
}
