package com.sau.gym.admin.rag.answer;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 作者:hfj
 * 功能:引用标签提取
 * 说明:
 * 只识别 [K1] 形式的引用标签；标签本身是否合法由 EvidenceRegistry 判定。
 */
public final class CitationExtractor {

    /**
     * 引用标签模式：[K数字]。
     */
    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[(K\\d+)]");

    private CitationExtractor() {
    }

    /**
     * 从回答文本中提取引用标签，保持出现顺序并去重。
     */
    public static List<String> extract(String answer) {
        Set<String> labels = new LinkedHashSet<>();

        if (StringUtils.hasText(answer)) {
            Matcher matcher = CITATION_PATTERN.matcher(answer);

            while (matcher.find()) {
                labels.add(matcher.group(1));
            }
        }

        return new ArrayList<>(labels);
    }

    /**
     * 判断回答是否至少包含一个引用标签。
     */
    public static boolean hasAnyCitation(String answer) {
        return !extract(answer).isEmpty();
    }
}
