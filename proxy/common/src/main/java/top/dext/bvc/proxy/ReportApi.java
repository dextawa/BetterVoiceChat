package top.dext.bvc.proxy;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import top.dext.bvc.api.proxy.ReportService;
import top.dext.bvc.api.proxy.VoiceReport;
import top.dext.bvc.proxy.report.ReportManager;
import top.dext.bvc.proxy.report.ReportStore;

/**
 * {@link ReportService} over the report manager.
 */
final class ReportApi implements ReportService {

    private final ReportManager reports;
    private final Clock clock;

    ReportApi(ReportManager reports, Clock clock) {
        this.reports = reports;
        this.clock = clock;
    }

    @Override
    public CompletableFuture<Long> report(UUID reporter, String reporterName, UUID target, String targetName, String server,
                                          String reason) {
        return reports.report(reporter, reporterName, target, targetName, server, reason, clock.millis(), true)
                .thenApply(filed -> filed.result() == ReportManager.Result.CREATED ? filed.id() : -1L);
    }

    @Override
    public CompletableFuture<List<VoiceReport>> reports(String status, int limit) {
        ReportStore.Status filter = status == null ? null : parse(status);
        return reports.store().list(filter, Math.max(1, Math.min(500, limit)), 0).thenApply(list -> {
            List<VoiceReport> out = new ArrayList<>(list.size());
            for (ReportStore.Report report : list) {
                out.add(view(report));
            }
            return out;
        });
    }

    @Override
    public CompletableFuture<Void> setStatus(long id, String status, String operator, String note) {
        ReportStore.Status target = parse(status);
        if (target == ReportStore.Status.COLLECTING) {
            throw new IllegalArgumentException("COLLECTING cannot be set");
        }
        boolean reopen = target == ReportStore.Status.OPEN;
        return reports.store().setStatus(id, target, reopen ? null : (operator == null ? "api" : operator), clock.millis(), note);
    }

    private static ReportStore.Status parse(String status) {
        return ReportStore.Status.valueOf(status.trim().toUpperCase(Locale.ROOT));
    }

    static VoiceReport view(ReportStore.Report r) {
        return new VoiceReport() {
            @Override
            public long id() {
                return r.id();
            }

            @Override
            public UUID reporter() {
                return r.reporter();
            }

            @Override
            public String reporterName() {
                return r.reporterName();
            }

            @Override
            public UUID target() {
                return r.target();
            }

            @Override
            public String targetName() {
                return r.targetName();
            }

            @Override
            public String server() {
                return r.server();
            }

            @Override
            public String reason() {
                return r.reason();
            }

            @Override
            public long createdAt() {
                return r.createdAt();
            }

            @Override
            public String status() {
                return r.status().name();
            }

            @Override
            public String toString() {
                return "VoiceReport#" + r.id() + " " + r.reporterName() + "->" + r.targetName();
            }
        };
    }
}
