package com.example.stuart.agent;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class GuardrailsTest {

    private final Guardrails guard = new Guardrails(Set.of("writeFile"), 1000, 1);

    @Test
    void signatureIgnoresKeyOrderAndWhitespace() {
        assertThat(Guardrails.signature("grep", "{\"regex\":\"foo\", \"glob\":\"**/*.java\"}"))
                .isEqualTo(Guardrails.signature("grep", "{ \"glob\": \"**/*.java\", \"regex\": \"foo\" }"));
    }

    @Test
    void repeatedReadIsDetectedUntilSomethingIsWritten() {
        String read = Guardrails.signature("readFile", "{\"path\":\"A.java\"}");
        guard.record("readFile", read);
        assertThat(guard.isRepeat("readFile", read)).isTrue();

        guard.record("writeFile", Guardrails.signature("writeFile", "{\"path\":\"A.java\",\"content\":\"x\"}"));
        assertThat(guard.isRepeat("readFile", read)).isFalse();
    }

    @Test
    void detectsCycles() {
        for (String s : new String[]{"a", "b", "c", "a", "b"}) {
            guard.record("t", s);
            assertThat(guard.inCycle()).isFalse();
        }
        guard.record("t", "c");
        assertThat(guard.inCycle()).isTrue();
    }

    @Test
    void escalatesFromNudgeToStop() {
        assertThat(guard.escalate("loop").action()).isEqualTo(Guardrails.Action.NUDGE);
        assertThat(guard.escalate("loop").action()).isEqualTo(Guardrails.Action.STOP);
    }

    @Test
    void budgetWarnsOnceThenStops() {
        assertThat(guard.checkBudget(100).action()).isEqualTo(Guardrails.Action.CONTINUE);
        assertThat(guard.checkBudget(850).action()).isEqualTo(Guardrails.Action.NUDGE);
        assertThat(guard.checkBudget(900).action()).isEqualTo(Guardrails.Action.CONTINUE);
        assertThat(guard.checkBudget(1000).action()).isEqualTo(Guardrails.Action.STOP);
    }
}
