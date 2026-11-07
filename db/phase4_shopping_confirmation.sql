-- ============================================================
-- Gym-Agent 第四轮迁移：商品确认回执表
-- 执行前请备份；本脚本只新增表，不修改既有数据。
-- ============================================================

-- 1. 商品确认回执表
--    confirmation_id 为主键：同一业务意图由数据库唯一约束收敛；
--    回执与订单、明细、库存、余额、流水在同一事务中写入。
CREATE TABLE IF NOT EXISTS shopping_confirmation (
    confirmation_id VARCHAR(64)  NOT NULL COMMENT '业务确认标识',
    user_id         BIGINT       NOT NULL COMMENT '用户ID',
    session_id      VARCHAR(64)  DEFAULT NULL COMMENT '会话ID',
    snapshot_hash   VARCHAR(64)  DEFAULT NULL COMMENT '服务端快照摘要（商品/数量/价格的指纹）',
    order_id        BIGINT       DEFAULT NULL COMMENT '订单ID',
    order_no        VARCHAR(64)  DEFAULT NULL COMMENT '订单号',
    status          VARCHAR(32)  NOT NULL COMMENT '办理结果：PAID/PENDING_PAYMENT 等',
    create_time     DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (confirmation_id),
    KEY idx_sc_user_id (user_id),
    KEY idx_sc_order_no (order_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '商品确认回执';

-- 2. user_balance 重复数据检查（部署前置步骤，人工核对）：
--    每个用户应只有一条有效余额记录；存在重复时需人工合并后
--    再考虑添加唯一约束（本轮不自动添加、不自动清理数据）。
-- SELECT user_id, COUNT(*) AS cnt
-- FROM user_balance
-- GROUP BY user_id
-- HAVING COUNT(*) > 1;

-- 3. 回滚脚本：
-- DROP TABLE IF EXISTS shopping_confirmation;
