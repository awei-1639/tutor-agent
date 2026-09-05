package com.tutor.knowledge.document;

import com.tutor.platform.config.ClamAvProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClamAvScannerTest {
    @Test
    void disabledScannerNeverNeedsNetwork() {
        ClamAvScanner scanner = new ClamAvScanner(new ClamAvProperties(false, "", 0, 1));

        assertThatCode(() -> scanner.scan("file".getBytes())).doesNotThrowAnyException();
    }

    @Test
    void enabledUnavailableScannerFailsClosed() {
        ClamAvScanner scanner = new ClamAvScanner(new ClamAvProperties(true, "127.0.0.1", 1, 1));

        assertThatThrownBy(() -> scanner.scan("file".getBytes()))
                .hasMessageContaining("恶意文件扫描服务暂不可用");
    }
}
