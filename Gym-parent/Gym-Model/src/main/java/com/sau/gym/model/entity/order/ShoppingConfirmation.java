package com.sau.gym.model.entity.order;

import com.sau.gym.model.entity.base.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Date;

/**
 * 作者:hfj
 * 功能:商品确认回执实体
 * 说明:
 * 与订单同事务写入：同一 confirmationId 由主键唯一约束收敛，
 * 重复确认通过回执定位同一个已提交订单。
 */
@Data
@Schema(description = "商品确认回执实体类")
public class ShoppingConfirmation extends BaseEntity {

    private static final long serialVersionUID = 1L;

    @Schema(description = "业务确认标识")
    private String confirmationId;

    @Schema(description = "用户ID")
    private Long userId;

    @Schema(description = "会话ID")
    private String sessionId;

    @Schema(description = "服务端快照摘要")
    private String snapshotHash;

    @Schema(description = "订单ID")
    private Long orderId;

    @Schema(description = "订单号")
    private String orderNo;

    @Schema(description = "办理结果状态")
    private String status;

    @Schema(description = "创建时间")
    private Date createTime;
}
