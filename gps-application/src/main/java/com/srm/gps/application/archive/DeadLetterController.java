package com.srm.gps.application.archive;

import com.srm.gps.application.archive.DeadLetterService.DeadLetterRow;
import com.srm.gps.application.archive.DeadLetterService.NotPossibleException;
import com.srm.gps.application.archive.DeadLetterService.ReplayReport;
import com.srm.gps.application.archive.DeadLetterService.Summary;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * The dead-letter archive (PostgreSQL dead_letter).
 *
 *   GET  /api/dead-letters?status=PARKED&topic=&limit=100   newest first, without stack traces
 *   GET  /api/dead-letters/summary                          counts by status, by original topic, by exception
 *   GET  /api/dead-letters/{id}                             one dead letter with its stack trace
 *   POST /api/dead-letters/{id}/replay                      back to its original topic (confirmed), then REPLAYED
 *   POST /api/dead-letters/replay?topic=&limit=200          the oldest parked ones, one by one
 *   POST /api/dead-letters/{id}/discard?note=               not to be replayed, with the reason (the row stays)
 */
@RestController
@RequestMapping("/api/dead-letters")
public class DeadLetterController {

    private final DeadLetterService deadLetters;

    public DeadLetterController(DeadLetterService deadLetters) {
        this.deadLetters = deadLetters;
    }

    @GetMapping
    public List<DeadLetterRow> list(@RequestParam(required = false) String status, @RequestParam(required = false) String topic,
                                    @RequestParam(defaultValue = "100") int limit) {
        return deadLetters.list(status, topic, Math.clamp(limit, 1, 1_000));
    }

    @GetMapping("/summary")
    public Summary summary() {
        return deadLetters.summary();
    }

    @GetMapping("/{id}")
    public DeadLetterRow one(@PathVariable long id) {
        return deadLetters.find(id);
    }

    @PostMapping("/{id}/replay")
    public DeadLetterRow replay(@PathVariable long id) {
        return deadLetters.replay(id);
    }

    @PostMapping("/replay")
    public ReplayReport replayParked(@RequestParam(required = false) String topic, @RequestParam(defaultValue = "200") int limit) {
        return deadLetters.replayParked(topic, Math.clamp(limit, 1, 5_000));
    }

    @PostMapping("/{id}/discard")
    public DeadLetterRow discard(@PathVariable long id, @RequestParam(required = false) String note) {
        return deadLetters.discard(id, note);
    }

    @ExceptionHandler(NotPossibleException.class)
    public ResponseEntity<Map<String, String>> notPossible(NotPossibleException problem) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", problem.getMessage()));
    }
}
