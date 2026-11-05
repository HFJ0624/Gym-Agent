package com.sau.gym.admin.agent.harness.interceptor;

import com.sau.gym.admin.agent.tool.executor.AgentToolExecuteResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 作者:hfj
 * 功能:工具执行责任链
 */
public class ToolInvocationChain {

    /**
     * 链尾执行器。
     */
    public interface Terminal {
        AgentToolExecuteResult invoke(ToolCallRequest request);
    }

    private final List<ToolInvocationInterceptor> interceptors;

    private final Terminal terminal;

    private final int index;

    public ToolInvocationChain(List<ToolInvocationInterceptor> interceptors, Terminal terminal) {
        this(interceptors, terminal, 0);
    }

    private ToolInvocationChain(List<ToolInvocationInterceptor> interceptors, Terminal terminal, int index) {
        List<ToolInvocationInterceptor> sorted = new ArrayList<>(
                interceptors == null ? List.of() : interceptors
        );
        sorted.sort(Comparator.comparingInt(ToolInvocationInterceptor::order));

        this.interceptors = sorted;
        this.terminal = terminal;
        this.index = index;
    }

    /**
     * 继续执行链。
     */
    public AgentToolExecuteResult proceed(ToolCallRequest request) {
        if (index < interceptors.size()) {
            return interceptors.get(index).intercept(
                    request,
                    new ToolInvocationChain(interceptors, terminal, index + 1)
            );
        }

        return terminal.invoke(request);
    }
}
