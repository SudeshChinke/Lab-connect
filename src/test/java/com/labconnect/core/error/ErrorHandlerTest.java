package com.labconnect.core.error;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class ErrorHandlerTest {

    @Test
    @Timeout(5)
    void testHandleSuccess() {
        String result = ErrorHandler.handle(() -> "success", e -> fail("Should not be called"));
        assertThat(result).isEqualTo("success");
    }

    @Test
    @Timeout(5)
    void testHandleLabConnectException() {
        String result = ErrorHandler.handle(() -> {
            throw new LabConnectException(ErrorCode.CONNECTION_FAILED, "Test error");
        }, e -> {
            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONNECTION_FAILED);
        });

        assertThat(result).isNull();
    }

    @Test
    @Timeout(5)
    void testHandleGenericException() {
        String result = ErrorHandler.handle(() -> {
            throw new RuntimeException("Generic error");
        }, e -> {
            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR);
            assertThat(e.getCause()).isInstanceOf(RuntimeException.class);
        });

        assertThat(result).isNull();
    }

    @Test
    @Timeout(5)
    void testHandleRunnable() {
        AtomicInteger counter = new AtomicInteger(0);

        ErrorHandler.handle(() -> {
            counter.incrementAndGet();
            throw new LabConnectException(ErrorCode.CONNECTION_FAILED, "Test");
        }, e -> {});

        assertThat(counter.get()).isEqualTo(1);
    }

    @Test
    @Timeout(10)
    void testRetrySuccess() throws Exception {
        AtomicInteger attempts = new AtomicInteger(0);

        String result = ErrorHandler.withRetry(() -> {
            int attempt = attempts.incrementAndGet();
            if (attempt < 3) {
                throw new LabConnectException(ErrorCode.CONNECTION_FAILED, "Attempt " + attempt);
            }
            return "success";
        }, 3, 10);

        assertThat(result).isEqualTo("success");
        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    @Timeout(10)
    void testRetryExhausted() {
        AtomicInteger attempts = new AtomicInteger(0);

        assertThatThrownBy(() ->
            ErrorHandler.withRetry(() -> {
                attempts.incrementAndGet();
                throw new LabConnectException(ErrorCode.CONNECTION_FAILED, "Fail");
            }, 2, 10)
        ).isInstanceOf(LabConnectException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CONNECTION_FAILED);

        assertThat(attempts.get()).isEqualTo(3); // initial + 2 retries
    }

    @Test
    @Timeout(10)
    void testRetryNonRecoverable() {
        AtomicInteger attempts = new AtomicInteger(0);

        assertThatThrownBy(() ->
            ErrorHandler.withRetry(() -> {
                attempts.incrementAndGet();
                throw new LabConnectException(ErrorCode.OUT_OF_MEMORY, "OOM", "Out of memory error", false);
            }, 3, 10)
        ).isInstanceOf(LabConnectException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.OUT_OF_MEMORY);

        // Should not retry non-recoverable
        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    @Timeout(10)
    void testExponentialBackoff() {
        AtomicInteger attempts = new AtomicInteger(0);
        long startTime = System.currentTimeMillis();

        assertThatThrownBy(() ->
            ErrorHandler.withExponentialBackoff(() -> {
                attempts.incrementAndGet();
                throw new LabConnectException(ErrorCode.CONNECTION_FAILED, "Fail");
            }, 3, 10)
        ).isInstanceOf(LabConnectException.class);

        long elapsed = System.currentTimeMillis() - startTime;
        // 10ms + 20ms + 40ms = 70ms minimum
        assertThat(attempts.get()).isEqualTo(4); // initial + 3 retries
    }

    @Test
    @Timeout(5)
    void testWrapException() {
        LabConnectException wrapped = ErrorHandler.wrap(
            new LabConnectException(ErrorCode.CONNECTION_FAILED, "Original"),
            ErrorCode.INTERNAL_ERROR
        );

        assertThat(wrapped.getErrorCode()).isEqualTo(ErrorCode.CONNECTION_FAILED);

        LabConnectException wrapped2 = ErrorHandler.wrap(
            new RuntimeException("Generic"),
            ErrorCode.CONNECTION_REFUSED
        );

        assertThat(wrapped2.getErrorCode()).isEqualTo(ErrorCode.CONNECTION_REFUSED);
    }

    @Test
    @Timeout(5)
    void testUnwrapChecked() throws Exception {
        String result = ErrorHandler.unwrapChecked(() -> "success", ErrorCode.INTERNAL_ERROR);
        assertThat(result).isEqualTo("success");

        assertThatThrownBy(() ->
            ErrorHandler.unwrapChecked(() -> { throw new RuntimeException("oops"); }, ErrorCode.FILE_NOT_FOUND)
        ).isInstanceOf(LabConnectException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FILE_NOT_FOUND);
    }

    @Test
    @Timeout(5)
    void testSafeSuccess() {
        ErrorHandler.Result<String> result = ErrorHandler.safe(() -> "success");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getValue()).isEqualTo("success");
        assertThat(result.getError()).isNull();
    }

    @Test
    @Timeout(5)
    void testSafeFailure() {
        ErrorHandler.Result<String> result = ErrorHandler.safe(() -> {
            throw new LabConnectException(ErrorCode.CONNECTION_FAILED, "fail");
        });

        assertThat(result.isFailure()).isTrue();
        assertThat(result.getError()).isNotNull();
        assertThat(result.getError().getErrorCode()).isEqualTo(ErrorCode.CONNECTION_FAILED);
    }

    @Test
    @Timeout(5)
    void testResultOrElse() {
        ErrorHandler.Result<String> success = ErrorHandler.Result.success("value");
        ErrorHandler.Result<String> failure = ErrorHandler.Result.failure(
            new LabConnectException(ErrorCode.CONNECTION_FAILED, "fail"));

        assertThat(success.orElse("default")).isEqualTo("value");
        assertThat(failure.orElse("default")).isEqualTo("default");
        assertThat(success.orElseGet(() -> "default")).isEqualTo("value");
        assertThat(failure.orElseGet(() -> "default")).isEqualTo("default");

        assertThatThrownBy(failure::orElseThrow)
            .isInstanceOf(LabConnectException.class);
    }
}