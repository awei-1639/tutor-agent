package com.tutor.coaching.push;

import com.tutor.identity.profile.ProfileService;
import com.tutor.identity.profile.SkillAlignService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PushServiceTest {
    @Test
    void scheduledRunProcessesEveryKnownUser() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ProfileService profiles = mock(ProfileService.class);
        PushService service = new PushService(jdbc, profiles, mock(SkillAlignService.class));
        when(jdbc.queryForList("SELECT id FROM users ORDER BY id", Long.class)).thenReturn(List.of(42L, 77L));
        when(profiles.snapshot(anyLong())).thenReturn(Map.of("skills", List.of()));

        service.scheduledRun();

        verify(profiles).snapshot(42L);
        verify(profiles).snapshot(77L);
    }
}
