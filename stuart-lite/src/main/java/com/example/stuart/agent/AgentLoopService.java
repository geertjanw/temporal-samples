package com.example.stuart.agent;

import com.example.stuart.agent.AgentResult.FinishReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;
import java.util.stream.Collectors;

/**
 * The generic ReAct loop ("thin model, thick harness").
 * <ol>
 *   <li>Send context + tool definitions to the model.</li>
 *   <li>The model only reasons and <i>asks</i> for tools; it never touches files itself.</li>
 *   <li>The harness validates each request (guardrails), executes it and appends the result.</li>
 *   <li>Repeat until the model answers with plain text (no tool calls).</li>
 * </ol>
 * Spring AI's internal tool execution is switched off so this class owns the loop.
 * <p>
 * Context strategy: the conversation is an append-only log (stable, cache-friendly prefix). Everything
 * dynamic - iteration counter, budget, tool ledger, nudges - lives in one small "memory" message at the
 * tail that is rebuilt every turn and never stored in the history.
 */
@Service
public class AgentLoopService {

    private static final Logger log = LoggerFactory.getLogger(AgentLoopService.class);
    private static final int MAX_TOOL_OUTPUT_CHARS = 20_000;
    private static final int LEDGER_TAIL = 40;

    private final ChatModel chatModel;

    public AgentLoopService(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * @param onIteration called at the start of each iteration (used to heartbeat the Temporal activity)
     */
    public AgentResult run(AgentRun run, IntConsumer onIteration) {
        ToolCallback[] callbacks = ToolCallbacks.from(run.tools().toArray());
        Map<String, ToolCallback> toolsByName = Arrays.stream(callbacks)
                .collect(Collectors.toMap(cb -> cb.getToolDefinition().name(), cb -> cb));

        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(callbacks)
                .internalToolExecutionEnabled(false) // we execute tools ourselves
                .build();

        List<Message> history = new ArrayList<>();
        history.add(new SystemMessage(run.systemPrompt()));
        history.add(new UserMessage(run.userPrompt()));

        Guardrails guard = new Guardrails(run.mutatingTools(), run.tokenBudget(), run.maxNudges());
        List<String> ledger = new ArrayList<>();
        String pendingNudge = null;
        long promptTokens = 0;
        long completionTokens = 0;
        int toolCalls = 0;

        for (int iteration = 1; iteration <= run.maxIterations(); iteration++) {
            onIteration.accept(iteration);

            List<Message> prompt = new ArrayList<>(history);
            prompt.add(new UserMessage(memoryTail(iteration, run, promptTokens + completionTokens, ledger, pendingNudge)));
            pendingNudge = null;

            ChatResponse response = chatModel.call(new Prompt(prompt, options));
            Usage usage = response.getMetadata().getUsage();
            promptTokens += nullToZero(usage.getPromptTokens());
            completionTokens += nullToZero(usage.getCompletionTokens());

            if (response.getResult() == null) {
                throw new IllegalStateException("Model returned no generation");
            }
            AssistantMessage answer = response.getResult().getOutput();
            history.add(answer);

            if (!answer.hasToolCalls()) {
                log.info("Agent finished after {} iterations, {} tool calls", iteration, toolCalls);
                return new AgentResult(answer.getText(), FinishReason.COMPLETED, iteration, toolCalls, promptTokens, completionTokens);
            }

            // --- harness: validate + execute every requested tool call ---
            List<ToolResponse> responses = new ArrayList<>();
            boolean repeated = false;
            for (ToolCall call : answer.getToolCalls()) {
                String signature = Guardrails.signature(call.name(), call.arguments());
                String output;
                if (guard.isRepeat(call.name(), signature)) {
                    repeated = true;
                    output = "Error: you already made this exact call and nothing has changed since. Use the earlier result.";
                } else {
                    output = execute(toolsByName.get(call.name()), call);
                    guard.record(call.name(), signature);
                    toolCalls++;
                }
                ledger.add(abbreviate(call.name() + " " + call.arguments(), 160) + " -> " + firstLine(output));
                responses.add(new ToolResponse(call.id(), call.name(), truncate(output)));
            }
            history.add(new ToolResponseMessage(responses));

            // --- guardrails: repetition first, then lack of progress / budget ---
            Guardrails.Verdict verdict = Guardrails.Verdict.CONTINUE;
            if (repeated) {
                verdict = guard.escalate("You repeated a tool call you had already made.");
            } else if (guard.inCycle()) {
                verdict = guard.escalate("You are looping through the same sequence of tool calls.");
            }
            if (verdict.action() == Guardrails.Action.STOP) {
                return stopped(FinishReason.LOOP_DETECTED, verdict, iteration, toolCalls, promptTokens, completionTokens);
            }
            Guardrails.Verdict budget = guard.checkBudget(promptTokens + completionTokens);
            if (budget.action() == Guardrails.Action.STOP) {
                return stopped(FinishReason.TOKEN_BUDGET, budget, iteration, toolCalls, promptTokens, completionTokens);
            }
            pendingNudge = join(verdict.message(), budget.message());
        }
        return new AgentResult("Stopped: reached max iterations (" + run.maxIterations() + ").",
                FinishReason.MAX_ITERATIONS, run.maxIterations(), toolCalls, promptTokens, completionTokens);
    }

    private String execute(ToolCallback tool, ToolCall call) {
        if (tool == null) {
            return "Error: unknown tool '" + call.name() + "'";
        }
        try {
            return tool.call(call.arguments() == null || call.arguments().isBlank() ? "{}" : call.arguments());
        } catch (Exception e) {
            // never let a bad tool call kill the loop; the model can read the error and recover
            return "Error: " + e.getMessage();
        }
    }

    /** The only part of the prompt that changes every turn - kept at the very end, outside the cached prefix. */
    private static String memoryTail(int iteration, AgentRun run, long tokensUsed, List<String> ledger, String nudge) {
        StringBuilder sb = new StringBuilder("<memory>\n")
                .append("iteration: ").append(iteration).append('/').append(run.maxIterations()).append('\n')
                .append("tokens used: ").append(tokensUsed).append('/').append(run.tokenBudget()).append('\n');
        if (!ledger.isEmpty()) {
            sb.append("tool ledger (most recent last):\n");
            ledger.subList(Math.max(0, ledger.size() - LEDGER_TAIL), ledger.size())
                    .forEach(line -> sb.append("- ").append(line).append('\n'));
        }
        if (nudge != null) {
            sb.append("WARNING: ").append(nudge).append('\n');
        }
        return sb.append("</memory>").toString();
    }

    private static AgentResult stopped(FinishReason reason, Guardrails.Verdict verdict, int iteration,
                                       int toolCalls, long prompt, long completion) {
        log.warn("Agent hard-stopped: {} ({})", reason, verdict.message());
        return new AgentResult("Stopped by guardrail: " + verdict.message(), reason, iteration, toolCalls, prompt, completion);
    }

    private static long nullToZero(Integer value) {
        return value == null ? 0 : value;
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() <= MAX_TOOL_OUTPUT_CHARS ? s
                : s.substring(0, MAX_TOOL_OUTPUT_CHARS) + "\n... [truncated " + (s.length() - MAX_TOOL_OUTPUT_CHARS) + " chars]";
    }

    private static String firstLine(String s) {
        if (s == null || s.isEmpty()) return "(empty)";
        int nl = s.indexOf('\n');
        return abbreviate(nl < 0 ? s : s.substring(0, nl), 100);
    }

    private static String abbreviate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    private static String join(String a, String b) {
        if (a == null) return b;
        if (b == null) return a;
        return a + " " + b;
    }
}
