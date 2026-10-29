# Reports – end of the day, end of the month 📊

The **Reports** section of the console answers what a fleet owner asks at the end of a day or a month:
what did every vehicle do, where did it stand and for how long, how long did the engine idle, what did the fuel
cost, and **how much could have been saved**. Every number has a **"How is this calculated?"** drop-down that shows
the formula with that number's own inputs.

| Page | What it shows |
|---|---|
| **Day report** (`/reports`) | Every vehicle on one day (midnight to midnight IST): distance, driving / standing time, stops, idling, max speed, over-speed, fuel, cost, could-save. Click a row for its full breakdown. Print / PDF, CSV |
| **Month report** (`/reports/month`) | The same for a month, plus the fleet day by day (distance, fuel cost or could-save per day) |
| **Vehicle day sheet** (`/reports/vehicle`) | One vehicle, one day, in time order: each trip (km, speed) and each stop (length, place, engine on or off, idling inside it) and its events |
| **Fuel & savings** (`/reports/savings`) | The business case: rupees lost to idling and fast driving, who loses the most, and a target (sliders) worth X a month and a year, with CO₂ |
| **Cost assumptions** (`/reports/assumptions`) | Fuel type and price, mileage, idle burn, economical speed, fast-driving penalty, CO₂ per litre. Saving re-prices every report at once |
| **Clear database** (`/reports/clear`) | Empty the database after printing the report; the reports keep their numbers by default |

Filters on every page: **day or month, company, vehicle** (drop-downs). The choice is kept in the address, so a
report can be bookmarked or printed again.

## The formulas

```
distance              sum of distance_from_previous_m of the day's positions (jitter < 10 m and impossible jumps filtered)
driving / standing    gaps between consecutive readings, by the later reading's MOVING / STOPPED status
                      (a driving gap over 10 min - device off, no coverage - counts as neither; a standing gap
                      counts up to 1 h, because a parked tracker reports rarely)
idling                IDLING episodes (engine on, standing longer than 5 min), overlapping ones once, between the
                      vehicle's first and last reading of the day, never more than its standing time
stops, trips          gps_stop / gps_trip that started that day (within 200 m for 3 min = a stop)
km above economical   distance of readings faster than the economical speed (default 60 km/h)

driving litres        distance km / mileage km per L
idle litres           idling h x idle burn L per h
fast-driving extra    km above economical speed / mileage x penalty %
fuel used             driving + idle + fast-driving extra
could be saved        idle + fast-driving extra       (switch off when standing, steadier speed)
rupees                litres x price per litre
CO2                   litres x 2.68 kg (diesel) or 2.31 kg (petrol)
```

Defaults (a loaded diesel truck in India): ₹92/L, 4 km/L, 2.5 L/h idling, 60 km/h economical speed, 20 % extra
above it. Change them to the fleet's own figures on **Cost assumptions**. They are estimates until a fuel sensor or
CAN-bus fuel reading is connected; the code that prices them is `FuelEstimator` (gps-processing, unit-tested).

## How it is built (and why it holds up with 150 real vehicles)

```
gps_position, gps_stop, gps_trip, vehicle_event      (written by the Kafka consumers, millions of rows)
        │
        │  DaySummaryJob, every 5 min: today + yesterday         one SQL statement per day (DaySummarizer)
        │  once after start: every day still in PostgreSQL      backfill, at most 62 days back
        ▼
vehicle_day_summary   one row per vehicle per day            150 vehicles -> 150 rows a day, ~4,650 a month
        │
        │  GET /api/reports/day | month | vehicle-day             priced with report_setting at read time
        ▼
Reports pages (Day, Month, Vehicle day sheet, Fuel & savings)
```

- **Reports never scan positions.** A month report for 150 vehicles reads about 4,650 summary rows, however many
  positions there are. Only the vehicle day sheet reads raw rows, and only one vehicle's day of them.
- **The summary job reads one day at a time**, through the new index `gps_position (recorded_at)`. At one reading
  a minute, 150 vehicles make about 216,000 positions a day; at one every 10 seconds about 1.3 million. Two days
  every 5 minutes stays a short query either way. If readings get much denser, the job can switch to adding only
  the new rows (an incremental summary) without changing the reports.
- **Prices are applied when a report is read**, not stored: a new fuel price re-prices every past report at once.
  Only the economical speed is used while summarizing (km above it); changing it rebuilds the stored days in the
  background.
- **Clear database keeps the reports.** It first rebuilds and freezes the live summary rows (`generation` > 0),
  then empties the raw tables; reports add up all generations of a day. Untick "Keep the day and month reports"
  to empty them too.
- Days are midnight to midnight in `gps.reports.zone` (Asia/Kolkata). Settings: `gps.reports.*` in application.yml.

## API

| Method | Path | |
|---|---|---|
| GET | `/api/reports/filters` | companies, vehicles, today |
| GET | `/api/reports/day?date=2026-10-29&tenant=&imei=` | every vehicle on one day, fleet totals |
| GET | `/api/reports/month?month=2026-10&tenant=&imei=` | every vehicle over a month, the fleet per day |
| GET | `/api/reports/vehicle-day?imei=&date=` | one vehicle's trips, stops and events in time order |
| POST | `/api/reports/refresh?date=` | rebuild a day's summaries now |
| GET / PUT | `/api/reports/assumptions` | the cost assumptions |
| POST | `/api/admin/reset?keepReports=true&restartFleet=true` | Clear database |
