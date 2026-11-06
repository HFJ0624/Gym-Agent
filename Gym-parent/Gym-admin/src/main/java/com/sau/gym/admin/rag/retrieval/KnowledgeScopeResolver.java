package com.sau.gym.admin.rag.retrieval;

import com.sau.gym.admin.mapper.CourtMapper;
import com.sau.gym.admin.mapper.VenueMapper;
import com.sau.gym.admin.agent.memory.model.AgentBusinessContext;
import com.sau.gym.model.entity.venue.Court;
import com.sau.gym.model.entity.venue.Venue;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 作者:hfj
 * 功能:知识检索范围解析器
 * 说明:
 * 1. 模型 arguments 未提供范围时，从可信业务上下文（当前页面/会话已验证槽位）补齐，
 *    不能仅依赖模型记得传 ID。
 * 2. 仅给 courtId 时，通过权威场地数据推导所属场馆。
 * 3. 同时给出 courtId 与 venueId 时，用权威场地数据校验真实归属，不一致直接判无效。
 * 4. 两个 ID 都没有时返回“无范围”模式：只允许平台级与真正全局的公共资料，
 *    不把任意场馆的规则当作全平台结论。
 * 5. 校验失败必须区分“不存在/不匹配”与“校验服务暂时不可用”，不允许静默放行。
 */
@Component
public class KnowledgeScopeResolver {

    private final CourtMapper courtMapper;

    private final VenueMapper venueMapper;

    public KnowledgeScopeResolver(CourtMapper courtMapper, VenueMapper venueMapper) {
        this.courtMapper = courtMapper;
        this.venueMapper = venueMapper;
    }

    /**
     * 解析结果。
     */
    public static class ScopeResolution {
        public final Long venueId;
        public final Long courtId;
        public final boolean scoped;
        public final boolean invalid;
        public final String reason;

        private ScopeResolution(Long venueId, Long courtId, boolean scoped, boolean invalid, String reason) {
            this.venueId = venueId;
            this.courtId = courtId;
            this.scoped = scoped;
            this.invalid = invalid;
            this.reason = reason;
        }

        public static ScopeResolution unscoped() {
            return new ScopeResolution(null, null, false, false, null);
        }

        public static ScopeResolution scoped(Long venueId, Long courtId) {
            return new ScopeResolution(venueId, courtId, true, false, null);
        }

        public static ScopeResolution invalid(String reason) {
            return new ScopeResolution(null, null, false, true, reason);
        }
    }

    /**
     * 解析本次知识检索的对象范围。
     *
     * @param requestedVenueId 模型给出的场馆ID，可为空
     * @param requestedCourtId 模型给出的场地ID，可为空
     * @param context          可信业务上下文，可为空
     */
    public ScopeResolution resolve(Long requestedVenueId, Long requestedCourtId, AgentBusinessContext context) {
        Long venueId = requestedVenueId;
        Long courtId = requestedCourtId;

        //1. 模型没有给范围时，从可信业务上下文补齐。
        //用户本轮明确给出新对象时走 requested 值，不继承旧槽位。
        if (venueId == null && courtId == null && context != null) {
            venueId = context.getLastVenueId();
            courtId = context.getLastCourtId();
        }

        if (venueId == null && courtId == null) {
            return ScopeResolution.unscoped();
        }

        //2. 权威场地归属校验与推导。
        if (courtId != null) {
            Court court;

            try {
                court = courtMapper.selectOne(courtId);
            } catch (Exception e) {
                //归属校验依赖权威数据，校验不可用时不能静默放行。
                return ScopeResolution.invalid("SCOPE_CHECK_UNAVAILABLE");
            }

            if (court == null) {
                return ScopeResolution.invalid("COURT_NOT_FOUND");
            }

            if (venueId != null && !venueId.equals(court.getVenueId())) {
                return ScopeResolution.invalid("COURT_VENUE_MISMATCH");
            }

            venueId = court.getVenueId();
        }

        //3. 场馆存在性校验。
        if (venueId != null) {
            try {
                List<Venue> venues = venueMapper.selectVenueBatchByIds(List.of(venueId));

                if (venues == null || venues.isEmpty()) {
                    return ScopeResolution.invalid("VENUE_NOT_FOUND");
                }
            } catch (Exception e) {
                return ScopeResolution.invalid("SCOPE_CHECK_UNAVAILABLE");
            }
        }

        return ScopeResolution.scoped(venueId, courtId);
    }

    /**
     * 解析跨场馆比较请求的场馆列表。
     * 只接受数字 ID，最多 5 个，非法输入返回 null 由调用方给出参数错误。
     */
    public List<Long> parseCompareVenueIds(String compareVenueIds) {
        if (!StringUtils.hasText(compareVenueIds)) {
            return null;
        }

        String[] parts = compareVenueIds.split("[,，]");
        List<Long> ids = new java.util.ArrayList<>();

        for (String part : parts) {
            String value = part.trim();
            if (!StringUtils.hasText(value)) {
                continue;
            }

            if (!value.matches("\\d{1,18}")) {
                return null;
            }

            long id = Long.parseLong(value);
            if (!ids.contains(id)) {
                ids.add(id);
            }
        }

        if (ids.isEmpty()) {
            return null;
        }

        //跨场馆比较限制在少量场馆内，避免被滥用成全库检索。
        return ids.size() > 5 ? ids.subList(0, 5) : ids;
    }
}
