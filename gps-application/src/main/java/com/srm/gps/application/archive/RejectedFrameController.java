package com.srm.gps.application.archive;

import com.srm.gps.application.archive.RejectedFrameArchive.RejectedFrameRow;
import com.srm.gps.application.archive.RejectedFrameArchive.Summary;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The rejected-frame archive (PostgreSQL rejected_frame).
 *
 *   GET /api/rejected-frames?imei=&limit=100   newest first, with the payload as it arrived
 *   GET /api/rejected-frames/summary           totals and counts by reason, last 24 h and all time
 */
@RestController
@RequestMapping("/api/rejected-frames")
public class RejectedFrameController {

    private final RejectedFrameArchive archive;

    public RejectedFrameController(RejectedFrameArchive archive) {
        this.archive = archive;
    }

    @GetMapping
    public List<RejectedFrameRow> list(@RequestParam(required = false) String imei, @RequestParam(defaultValue = "100") int limit) {
        return archive.list(imei, Math.clamp(limit, 1, 1_000));
    }

    @GetMapping("/summary")
    public Summary summary() {
        return archive.summary();
    }
}
