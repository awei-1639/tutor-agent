package com.tutor.knowledge;

import com.tutor.config.AliyunOcrProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class AliyunOcrClientTest {
    @Test
    void disabledClientDoesNotCallRemoteService() {
        AliyunOcrClient client = new AliyunOcrClient(new AliyunOcrProperties(false, "", "", "", 80, 100, 15));

        assertThatCode(() -> client.recognize(new byte[]{1, 2, 3})).doesNotThrowAnyException();
        assertThat(client.recognize(new byte[]{1, 2, 3})).isEmpty();
    }
}
