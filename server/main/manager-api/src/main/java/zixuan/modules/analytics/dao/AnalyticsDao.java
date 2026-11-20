package zixuan.modules.analytics.dao;

import java.time.LocalDate;
import java.util.Date;
import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Mapper
public interface AnalyticsDao {

    @Insert("INSERT INTO ai_client_event (event_date, user_id, name, props, created_at) "
            + "VALUES (#{date}, #{userId}, #{name}, #{props}, NOW(3))")
    int insertEvent(@Param("date") LocalDate date, @Param("userId") Long userId,
            @Param("name") String name, @Param("props") String props);

    /** 普通用户的注册时间。管理员不是产品的使用者，不计入留存。 */
    @Select("SELECT id AS userId, create_date AS createdAt FROM sys_user "
            + "WHERE super_admin = 0 AND create_date >= #{since}")
    List<SignupRow> signups(@Param("since") Date since);

    /** 每个用户在哪些天有过对话。 */
    @Select("SELECT user_id AS userId, usage_date AS day FROM ai_conversation_usage "
            + "WHERE usage_date >= #{since} AND first_party = 1 AND user_id <> 0 AND input_mode <> 'opening' "
            + "GROUP BY user_id, usage_date")
    List<ActivityRow> activeDays(@Param("since") LocalDate since);

    @Select("SELECT usage_date AS day, first_text_ms AS firstTextMs FROM ai_conversation_usage "
            + "WHERE usage_date >= #{since} AND first_text_ms IS NOT NULL AND input_mode <> 'opening'")
    List<LatencyRow> firstTextTimes(@Param("since") LocalDate since);

    @Select("SELECT event_date AS day, name, COUNT(*) AS total, COUNT(DISTINCT user_id) AS users "
            + "FROM ai_client_event WHERE event_date >= #{since} GROUP BY event_date, name "
            + "ORDER BY event_date DESC, total DESC")
    List<EventCountRow> eventCounts(@Param("since") LocalDate since);

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class SignupRow {
        private Long userId;
        private Date createdAt;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class ActivityRow {
        private Long userId;
        private LocalDate day;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class LatencyRow {
        private LocalDate day;
        private Integer firstTextMs;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class EventCountRow {
        private LocalDate day;
        private String name;
        private Long total;
        private Long users;
    }
}
