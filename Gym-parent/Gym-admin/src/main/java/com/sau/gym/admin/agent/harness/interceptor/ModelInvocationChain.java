package com.sau.gym.admin.agent.harness.interceptor;

import com.sau.gym.admin.agent.llm.LlmChatResponse;
import com.sau.gym.admin.agent.llm.LlmClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 作者:hfj
 * 功能:模型调用责任链
 * 说明:
 * 链尾是真正的 LlmClient 调用，前面每个拦截器都必须显式调用 proceed 才能继续。
 */
public class ModelInvocationChain {

    /**
     * 链尾执行器。
     */
    public interface Terminal {
        LlmChatResponse invoke(ModelCallRequest request);
    }

    private final List<ModelInvocationInterceptor> interceptors;

    private final Terminal terminal;

    private final int index;

    public ModelInvocationChain(List<ModelInvocationInterceptor> interceptors, Terminal terminal) {
        this(interceptors, terminal, 0);
    }

    private ModelInvocationChain(List<ModelInvocationInterceptor> interceptors, Terminal terminal, int index) {
        List<ModelInvocationInterceptor> sorted = new ArrayList<>(
                interceptors == null ? List.of() : interceptors
        );
        sorted.sort(Comparator.comparingInt(ModelInvocationInterceptor::order));

        this.interceptors = sorted;
        this.terminal = terminal;
        this.index = index;
    }

    /**
     * 继续执行链。
     */
    public LlmChatResponse proceed(ModelCallRequest request) {
        if (index < interceptors.size()) {
            return interceptors.get(index).intercept(
                    request,
                    new ModelInvocationChain(interceptors, terminal, index + 1)
            );
        }

        return terminal.invoke(request);
    }

    /**
     * 便捷构造:以 LlmClient 作为链尾。
     */
    public static ModelInvocationChain of(List<ModelInvocationInterceptor> interceptors, LlmClient client) {
        return new ModelInvocationChain(
                interceptors,
                request -> client.chat(request.toLlmChatRequest())
        );
    }
}
