-- Reusable reset: run with psql ON_ERROR_STOP or Spring ScriptUtils.
-- Only after 01-schema.sql, only in an isolated seat_test database.
BEGIN;
TRUNCATE ticketing.ticketing_outbox, ticketing.ticketing_inbox_events,
    ticketing.reconciliation_tasks, ticketing.booking_items, ticketing.bookings,
    queue.user_artist_fan_scores, concert.seats, concert.schedules,
    concert.concerts, concert.artist, auth.users RESTART IDENTITY;
INSERT INTO auth.users (id, email, password, name, fan_score, created_at)
VALUES (101, 'u-a@seat-test.invalid', 'disabled-test-login', 'U-A', 0, '2030-01-01'),
       (102, 'u-b@seat-test.invalid', 'disabled-test-login', 'U-B', 0, '2030-01-01'),
       (103, 'u-c@seat-test.invalid', 'disabled-test-login', 'U-C', 0, '2030-01-01');
INSERT INTO auth.users (id, email, password, name, fan_score, created_at)
SELECT 1000+n, 'u-'||lpad(n::text,3,'0')||'@seat-test.invalid',
       'disabled-test-login', 'U-'||lpad(n::text,3,'0'), 0, '2030-01-01'::timestamp
FROM generate_series(1,100) AS n;
INSERT INTO concert.artist (id, name) VALUES (11, 'Test artist A'), (12, 'Test artist B');
INSERT INTO concert.concerts
    (id, title, category, description, location, duration_minutes, is_visible, created_at, artist_id)
VALUES (201, 'C-A', 'TEST', 'Seat fixture', 'Test hall A', 120, true, '2030-01-01T00:00:00Z', 11),
       (202, 'C-B', 'TEST', 'Seat fixture', 'Test hall B', 120, true, '2030-01-01T00:00:00Z', 12);
INSERT INTO concert.schedules (id, concert_id, start_time, end_time, total_seats)
VALUES (301, 201, '2030-02-01T10:00:00Z', '2030-02-01T12:00:00Z', 9),
       (302, 201, '2030-02-02T10:00:00Z', '2030-02-02T12:00:00Z', 1),
       (303, 202, '2030-02-03T10:00:00Z', '2030-02-03T12:00:00Z', 1);
INSERT INTO concert.seats
    (id, section, row_number, seat_number, schedule_id, status, version, grade, price)
SELECT 400+n, 'A', 1, n, 301, CASE WHEN n=9 THEN 'RESERVED' ELSE 'AVAILABLE' END,
       0, 'TEST', 10000 FROM generate_series(1,9) AS n;
INSERT INTO concert.seats
    (id, section, row_number, seat_number, schedule_id, status, version, grade, price)
VALUES (410, 'A', 1, 1, 302, 'AVAILABLE', 0, 'TEST', 10000),
       (411, 'A', 1, 1, 303, 'AVAILABLE', 0, 'TEST', 10000);
INSERT INTO ticketing.bookings
    (id, user_id, schedule_id, total_price, status, created_at, expires_at, confirmed_at)
VALUES ('00000000-0000-0000-0000-000000000009', 103, 301, 10000, 'CONFIRMED',
        '2030-01-01T00:00:00Z', '2030-01-01T00:05:00Z', '2030-01-01T00:01:00Z');
INSERT INTO ticketing.booking_items (booking_id, seat_id)
VALUES ('00000000-0000-0000-0000-000000000009', 409);
SELECT setval(pg_get_serial_sequence('auth.users','id'),1100);
SELECT setval(pg_get_serial_sequence('concert.artist','id'),12);
SELECT setval(pg_get_serial_sequence('concert.concerts','id'),202);
SELECT setval(pg_get_serial_sequence('concert.schedules','id'),303);
SELECT setval(pg_get_serial_sequence('concert.seats','id'),411);
COMMIT;
