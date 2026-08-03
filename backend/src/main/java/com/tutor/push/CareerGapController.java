package com.tutor.push;

import com.tutor.auth.AuthContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class CareerGapController {
    private final CareerGapService gaps;

    public CareerGapController(CareerGapService gaps) {
        this.gaps = gaps;
    }

    @GetMapping("/career/gaps")
    public List<CareerGapService.GapCard> topGaps() {
        Long userId = AuthContext.currentUserId();
        if (userId == null) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.UNAUTHORIZED, "未认证");
        return gaps.topGaps(userId);
    }
}
