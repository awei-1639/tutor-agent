package com.tutor.knowledge;

import com.tutor.config.ClamAvProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** ClamAV clamd INSTREAM 适配器。启用扫描时始终采用失败即拒绝策略。 */
@Component
public class ClamAvScanner {
    private final ClamAvProperties properties;

    public ClamAvScanner(ClamAvProperties properties) {
        this.properties = properties;
    }

    public void scan(byte[] content) {
        if (!properties.enabled()) return;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(properties.effectiveHost(), properties.effectivePort()),
                    properties.effectiveTimeoutSeconds() * 1000);
            socket.setSoTimeout(properties.effectiveTimeoutSeconds() * 1000);
            var output = socket.getOutputStream();
            output.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
            writeChunk(output, content);
            output.write(new byte[]{0, 0, 0, 0});
            output.flush();
            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
            if (response.contains("FOUND")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件未通过恶意内容扫描");
            }
            if (!response.contains("OK")) {
                throw new IllegalStateException("ClamAV 返回异常结果");
            }
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "恶意文件扫描服务暂不可用，请稍后重试");
        }
    }

    private static void writeChunk(java.io.OutputStream output, byte[] content) throws java.io.IOException {
        int length = content.length;
        output.write(new byte[]{(byte) (length >>> 24), (byte) (length >>> 16), (byte) (length >>> 8), (byte) length});
        output.write(content);
    }
}
