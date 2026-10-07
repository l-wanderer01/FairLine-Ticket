package com.example.fairline.comparison;

/** Storage contract for experiments; rank is zero-based, as with Redis ZRANK. */
public interface ReservationStore {
    enum HoldResult { HELD, LIMIT_EXCEEDED, ALREADY_HELD }
    HoldResult hold(long concert, long schedule, long seat, String user);
    String owner(long concert, long schedule, long seat);
    boolean release(long concert, long schedule, long seat, String user);
    int releaseAll(long concert, long schedule, String user);
    void enter(long concert, long schedule, String user, long score);
    Long rank(long concert, long schedule, String user);
    boolean leaveQueue(long concert, long schedule, String user);
    String admit(long concert, long schedule, String user, int capacity);
    String consume(String token);
    long active(long concert, long schedule);
    long decrementActive(long concert, long schedule);
}
