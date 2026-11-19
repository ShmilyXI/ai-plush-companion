package zixuan.modules.conversation.dao;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import zixuan.common.dao.BaseDao;
import zixuan.modules.conversation.entity.ConversationUsageEntity;

@Mapper
public interface ConversationUsageDao extends BaseDao<ConversationUsageEntity> {

    @Select("SELECT COUNT(*) FROM ai_conversation_usage "
            + "WHERE usage_date = #{date} AND user_id = #{userId} AND first_party = 1 AND input_mode <> 'opening'")
    long countFirstPartyTurns(@Param("userId") Long userId, @Param("date") LocalDate date);

    /**
     * 每天的汇总：活跃用户数、轮数、token、平均首字耗时。
     * 轮数和活跃用户只算用户发起的对话；角色主动说的开场白单独一列，它的 token 和合成字数计入总量。
     */
    @Select("SELECT DATE_FORMAT(usage_date, '%Y-%m-%d') AS usageDate, SUM(input_mode <> 'opening') AS turns, "
            + "COUNT(DISTINCT CASE WHEN input_mode <> 'opening' THEN user_id END) AS users, "
            + "SUM(input_mode = 'audio') AS audioTurns, SUM(input_mode = 'opening') AS openings, "
            + "COALESCE(SUM(prompt_tokens), 0) AS promptTokens, "
            + "COALESCE(SUM(completion_tokens), 0) AS completionTokens, "
            + "COALESCE(SUM(tts_chars), 0) AS ttsChars, "
            + "ROUND(AVG(CASE WHEN input_mode <> 'opening' THEN first_text_ms END)) AS avgFirstTextMs "
            + "FROM ai_conversation_usage WHERE usage_date >= #{since} "
            + "GROUP BY usage_date ORDER BY usage_date DESC")
    List<Map<String, Object>> dailySummary(@Param("since") LocalDate since);

    /** 某一天用得最多的用户。 */
    @Select("SELECT user_id AS userId, SUM(input_mode <> 'opening') AS turns, SUM(input_mode = 'audio') AS audioTurns, "
            + "COALESCE(SUM(prompt_tokens), 0) AS promptTokens, "
            + "COALESCE(SUM(completion_tokens), 0) AS completionTokens "
            + "FROM ai_conversation_usage WHERE usage_date = #{date} "
            + "GROUP BY user_id ORDER BY turns DESC LIMIT #{limit}")
    List<Map<String, Object>> topUsers(@Param("date") LocalDate date, @Param("limit") int limit);
}
