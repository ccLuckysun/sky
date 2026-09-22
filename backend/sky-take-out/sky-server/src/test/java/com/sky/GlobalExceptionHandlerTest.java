package com.sky;

import com.sky.handler.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.SQLIntegrityConstraintViolationException;
import java.util.stream.Stream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    static Stream<Arguments> constraintMessages() {
        return Stream.of(
                Arguments.of("Duplicate entry 'zhangsan' for key 'employee.idx_username'", "'zhangsan'已存在"),
                Arguments.of("Duplicate entry 'zhang san' for key 'employee.idx_username'", "'zhang san'已存在"),
                Arguments.of("Duplicate entry 'O'Brien' for key 'employee.idx_username'", "'O'Brien'已存在"),
                Arguments.of("Cannot add or update a child row: a foreign key constraint fails", "未知错误"),
                Arguments.of("Duplicate entry", "未知错误"),
                Arguments.of(null, "未知错误")
        );
    }

    @ParameterizedTest
    @MethodSource("constraintMessages")
    void returnsUnifiedErrorForSqlConstraint(String message, String expected) throws Exception {
        assertError(new SQLIntegrityConstraintViolationException(message), expected);
    }

    @Test
    void handlesSqlConstraintWrappedBySpring() throws Exception {
        assertError(new DuplicateKeyException("insert failed",
                new SQLIntegrityConstraintViolationException(
                        "Duplicate entry 'zhangsan' for key 'employee.idx_username'")), "'zhangsan'已存在");
    }

    private void assertError(Exception exception, String expected) throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new FailingController(exception))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        mvc.perform(get("/constraint-test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value(expected));
    }

    @RestController
    static class FailingController {
        private final Exception exception;

        FailingController(Exception exception) {
            this.exception = exception;
        }

        @GetMapping("/constraint-test")
        public void fail() throws Exception {
            throw exception;
        }
    }
}
