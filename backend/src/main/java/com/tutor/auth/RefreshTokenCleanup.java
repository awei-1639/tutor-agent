package com.tutor.auth;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Owns refresh-token retention scheduling separately from authentication use cases. */
@Component
public class RefreshTokenCleanup {
    private final AuthStore store;

    public RefreshTokenCleanup(AuthStore store) {
        this.store = store;
    }

    @Scheduled(cron = "0 15 3 * * *")
    public void purge() {
        store.purgeRefreshTokens();
    }
}
