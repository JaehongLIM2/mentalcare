package com.mentalcare.trend;

import com.mentalcare.common.Dates;
import com.mentalcare.repository.CheckInRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** Weekly check-in series for the Trends screen. Numbers only; no interpretation. */
@Service
public class TrendService {

    public record TrendPoint(long sessionId, String date, Integer mood, Integer stress, Integer anxiety,
                             Integer energy, Integer sleep) {}

    private final CheckInRepository checkIns;

    public TrendService(CheckInRepository checkIns) {
        this.checkIns = checkIns;
    }

    /** Oldest first, at most {@code limit} points. */
    public List<TrendPoint> recent(int limit) {
        List<TrendPoint> points = new ArrayList<>();
        for (CheckInRepository.DatedCheckIn d : checkIns.findRecent(limit)) {
            var c = d.checkIn();
            if (c.isEmpty()) continue;
            points.add(new TrendPoint(c.sessionId(), Dates.isoDate(d.sessionStartedAt()),
                    c.mood(), c.stress(), c.anxiety(), c.energy(), c.sleep()));
        }
        java.util.Collections.reverse(points);
        return points;
    }
}
