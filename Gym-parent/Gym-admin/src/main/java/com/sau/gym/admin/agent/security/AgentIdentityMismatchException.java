package com.sau.gym.admin.agent.security;

/**
 * 作者:hfj
 * 功能:身份不一致异常
 * 说明:
 * 前端 query 里的 userId 只能用于兼容校验，
 * 一旦与登录态不一致必须拒绝，不能拿它去查询或执行工具。
 */
public class AgentIdentityMismatchException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AgentIdentityMismatchException(String message) {
        super(message);
    }
}
