package com.sau.gym.admin.agent.context;

import com.sau.gym.admin.agent.config.AgentHarnessProperties;
import com.sau.gym.admin.agent.harness.interceptor.ModelCallPurpose;
import com.sau.gym.admin.agent.harness.interceptor.ModelCallRequest;
import com.sau.gym.admin.agent.harness.interceptor.ModelInvocationChain;
import com.sau.gym.admin.agent.harness.interceptor.ModelInvocationInterceptor;
import com.sau.gym.admin.agent.llm.LlmChatResponse;
import com.sau.gym.admin.agent.llm.LlmClient;
import com.sau.gym.admin.agent.llm.LlmException;
import com.sau.gym.admin.agent.llm.LlmMessage;
import com.sau.gym.admin.agent.memory.model.SessionMessage;
import com.sau.gym.admin.agent.runtime.AgentBudgetExceededException;
import com.sau.gym.admin.agent.runtime.AgentRunContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 作者:hfj
 * 功能:会话历史自动摘要服务
 * 说明:
 * 1. 复用第一轮 LlmClient 与模型拦截器链，调用用途为 CONTEXT_SUMMARY，
 *    计入共享辅助调用预算，不在摘要时重新开始计时。
 * 2. 摘要内容只用于理解上下文：保留诉求、偏好、有来源事实、用户纠正、未完成问题；
 *    不把建议或未确认参数写成已确认业务事实，不生成确认码或授权。
 * 3. 摘要失败（超时、空响应、超长、格式异常）时返回失败，由调用方降级为完整轮次裁剪。
 */
@Component
public class AgentSessionSummarizer {

    private static final Logger log = LoggerFactory.getLogger(AgentSessionSummarizer.class);

    private final ModelInvocationChain modelChain;

    private final AgentHarnessProperties properties;

    @Autowired
    public AgentSessionSummarizer(List<ModelInvocationInterceptor> modelInterceptors,
                                  LlmClient llmClient,
                                  AgentHarnessProperties properties) {
        this.modelChain = ModelInvocationChain.of(modelInterceptors, llmClient);
        this.properties = properties;
    }

    /**
     * 摘要结果。
     */
    public static class SummarizeResult {
        public final boolean ok;
        public final String summary;
        public final String failureReason;

        private SummarizeResult(boolean ok, String summary, String failureReason) {
            this.ok = ok;
            this.summary = summary;
            this.failureReason = failureReason;
        }

        public static SummarizeResult success(String summary) {
            return new SummarizeResult(true, summary, null);
        }

        public static SummarizeResult failure(String reason) {
            return new SummarizeResult(false, null, reason);
        }
    }

    /**
     * 对移出的完整轮次做增量摘要。
     *
     * @param runContext      运行上下文，用于辅助调用预算与剩余时间
     * @param previousSummary 旧摘要，可为空
     * @param turns           被移出的完整轮次消息
     */
    public SummarizeResult summarize(AgentRunContext runContext,
                                     String previousSummary,
                                     List<SessionMessage> turns) {
        if (turns == null || turns.isEmpty()) {
            return SummarizeResult.failure("没有需要摘要的历史轮次");
        }

        String prompt = buildPrompt(previousSummary, turns);
        int maxTokens = properties.getSummaryMaxTokens() > 0 ? properties.getSummaryMaxTokens() : 800;

        try {
            ModelCallRequest request = ModelCallRequest.builder()
                    .runContext(runContext)
                    .messages(List.of(LlmMessage.user(prompt)))
                    .attempt(1)
                    .purpose(ModelCallPurpose.CONTEXT_SUMMARY)
                    .build();

            LlmChatResponse response = modelChain.proceed(request);

            String summary = response == null ? null : response.content();

            if (!StringUtils.hasText(summary)) {
                return SummarizeResult.failure("摘要模型返回空内容");
            }

            summary = summary.trim();

            //最小校验：长度受控，且不允许摘要自己生成确认凭证。
            if (TokenEstimator.estimate(summary) > maxTokens) {
                return SummarizeResult.failure("摘要超过长度预算");
            }

            if (summary.contains("确认码") || summary.contains("已确认预约成功")
                    || summary.contains("已取消成功")) {
                return SummarizeResult.failure("摘要包含不允许的状态表述");
            }

            return SummarizeResult.success(summary);

        } catch (AgentBudgetExceededException e) {
            //预算不足时跳过摘要，由调用方降级裁剪。
            log.warn("[AgentSummarizer] 摘要预算不足: {}", e.getReasonCode());
            return SummarizeResult.failure("摘要预算不足: " + e.getReasonCode());

        } catch (LlmException e) {
            log.warn("[AgentSummarizer] 摘要模型调用失败: {}", e.getMessage());
            return SummarizeResult.failure("摘要模型调用失败: " + e.getErrorType());
        }
    }

    /**
     * 构造摘要提示词。
     */
    private String buildPrompt(String previousSummary, List<SessionMessage> turns) {
        int maxChars = (properties.getSummaryMaxTokens() > 0 ? properties.getSummaryMaxTokens() : 800) * 2;

        StringBuilder builder = new StringBuilder();
        builder.append("请把下面的历史对话合并成一段简洁摘要，供客服后续理解上下文使用。\n\n");
        builder.append("【摘要要求】\n");
        builder.append("1. 保留：用户当前诉求与偏好、已确定的场馆/场地/日期/时段、用户明确纠正或放弃的信息、未完成的问题和下一步需要追问的点。\n");
        builder.append("2. 有来源的事实标注所属对象，例如“想订A场馆羽毛球场”。\n");
        builder.append("3. 不要把模型建议、未确认参数或用户假设写成已确认事实；不要出现确认码、成功/失败等业务结论。\n");
        builder.append("4. 历史里的订单、预约状态只是当时的观测，写明“当时查询”。\n");
        builder.append("5. 只输出摘要正文，不要解释。总长度不超过").append(maxChars).append("字。\n\n");

        if (StringUtils.hasText(previousSummary)) {
            builder.append("【旧摘要】\n").append(previousSummary).append("\n\n");
        }

        builder.append("【被移出的对话】\n");
        for (SessionMessage message : turns) {
            if (message == null || !StringUtils.hasText(message.getRole())) {
                continue;
            }

            String content = limit(message.getContent(), 300);

            if ("user".equals(message.getRole())) {
                builder.append("用户：").append(content).append("\n");
            } else if ("assistant".equals(message.getRole())) {
                builder.append("客服：").append(content).append("\n");
            } else if ("tool".equals(message.getRole())) {
                builder.append("工具结果：").append(content).append("\n");
            }
        }

        return builder.toString();
    }

    /**
     * 限制单条消息长度。
     */
    private String limit(String text, int maxChars) {
        if (!StringUtils.hasText(text)) {
            return "";
        }

        String value = text.replaceAll("\\s+", " ").trim();

        return value.length() <= maxChars ? value : value.substring(0, maxChars) + "...";
    }
}
