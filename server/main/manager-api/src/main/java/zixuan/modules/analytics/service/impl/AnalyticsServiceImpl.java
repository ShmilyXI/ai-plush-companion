package zixuan.modules.analytics.service.impl;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import cn.hutool.json.JSONObject;
import lombok.RequiredArgsConstructor;
import zixuan.modules.analytics.dao.AnalyticsDao;
import zixuan.modules.analytics.dao.AnalyticsDao.ActivityRow;
import zixuan.modules.analytics.dao.AnalyticsDao.EventCountRow;
import zixuan.modules.analytics.dao.AnalyticsDao.LatencyRow;
import zixuan.modules.analytics.dao.AnalyticsDao.SignupRow;
import zixuan.modules.analytics.dto.ClientEventsDTO;
import zixuan.modules.analytics.service.AnalyticsService;

@Service
@RequiredArgsConstructor
public class AnalyticsServiceImpl implements AnalyticsService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Pattern EVENT_NAME = Pattern.compile("^[a-z][a-z0-9_]{1,39}$");
    private static final Pattern PROP_NAME = Pattern.compile("^[a-z][a-z0-9_]{0,31}$");
    private static final int MAX_EVENTS_PER_REQUEST = 20;
    private static final int MAX_PROPS = 8;
    private static final int MAX_PROP_TEXT = 80;
    private static final int MAX_PROPS_LENGTH = 500;
    private static final int[] RETENTION_DAYS = { 1, 7, 30 };

    private final AnalyticsDao dao;

    @Override
    public int recordEvents(Long userId, ClientEventsDTO dto) {
        if (userId == null || dto == null || dto.getEvents() == null) {
            return 0;
        }
        LocalDate today = LocalDate.now(ZONE);
        int recorded = 0;
        for (ClientEventsDTO.Item item : dto.getEvents()) {
            if (recorded >= MAX_EVENTS_PER_REQUEST) {
                break;
            }
            if (item == null || item.getName() == null || !EVENT_NAME.matcher(item.getName()).matches()) {
                continue;
            }
            dao.insertEvent(today, userId, item.getName(), props(item.getProps()));
            recorded++;
        }
        return recorded;
    }

    @Override
    public Map<String, Object> overview(int days) {
        int safeDays = Math.min(Math.max(days, 1), 60);
        LocalDate today = LocalDate.now(ZONE);
        LocalDate since = today.minusDays(safeDays - 1L);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cohorts", cohorts(since, today));
        result.put("latency", latency(since));
        result.put("events", events(since));
        return result;
    }

    /**
     * 按注册日期分组的留存。第 N 日留存指注册后的第 N 天当天有过对话。
     * 第 N 天还没过完时不给数字，避免把“还没到”看成“没回来”。
     */
    private List<Map<String, Object>> cohorts(LocalDate since, LocalDate today) {
        Date sinceInstant = Date.from(since.atStartOfDay(ZONE).toInstant());
        Map<Long, Set<LocalDate>> activeDays = new HashMap<>();
        for (ActivityRow row : dao.activeDays(since)) {
            if (row.getUserId() != null && row.getDay() != null) {
                activeDays.computeIfAbsent(row.getUserId(), key -> new HashSet<>()).add(row.getDay());
            }
        }
        Map<LocalDate, List<Long>> bySignupDay = new TreeMap<>(Collections.reverseOrder());
        for (SignupRow row : dao.signups(sinceInstant)) {
            if (row.getUserId() == null || row.getCreatedAt() == null) {
                continue;
            }
            // 注册时间是一个时刻，按上海时间折算成日期，不依赖数据库连接的时区设置。
            LocalDate day = row.getCreatedAt().toInstant().atZone(ZONE).toLocalDate();
            bySignupDay.computeIfAbsent(day, key -> new ArrayList<>()).add(row.getUserId());
        }
        List<Map<String, Object>> cohorts = new ArrayList<>();
        for (Map.Entry<LocalDate, List<Long>> entry : bySignupDay.entrySet()) {
            LocalDate signupDay = entry.getKey();
            List<Long> users = entry.getValue();
            Map<String, Object> cohort = new LinkedHashMap<>();
            cohort.put("date", signupDay.toString());
            cohort.put("size", users.size());
            cohort.put("activated", users.stream()
                    .filter(user -> !activeDays.getOrDefault(user, Set.of()).isEmpty()).count());
            for (int offset : RETENTION_DAYS) {
                LocalDate target = signupDay.plusDays(offset);
                cohort.put("d" + offset, target.isBefore(today)
                        ? users.stream().filter(user -> activeDays.getOrDefault(user, Set.of()).contains(target)).count()
                        : null);
            }
            cohorts.add(cohort);
        }
        return cohorts;
    }

    /** 每天首字耗时的中位数和 90 分位。 */
    private List<Map<String, Object>> latency(LocalDate since) {
        Map<LocalDate, List<Integer>> byDay = new TreeMap<>(Collections.reverseOrder());
        for (LatencyRow row : dao.firstTextTimes(since)) {
            if (row.getDay() != null && row.getFirstTextMs() != null && row.getFirstTextMs() >= 0) {
                byDay.computeIfAbsent(row.getDay(), key -> new ArrayList<>()).add(row.getFirstTextMs());
            }
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<LocalDate, List<Integer>> entry : byDay.entrySet()) {
            List<Integer> values = entry.getValue();
            Collections.sort(values);
            Map<String, Object> day = new LinkedHashMap<>();
            day.put("date", entry.getKey().toString());
            day.put("turns", values.size());
            day.put("p50", percentile(values, 50));
            day.put("p90", percentile(values, 90));
            result.add(day);
        }
        return result;
    }

    private List<Map<String, Object>> events(LocalDate since) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (EventCountRow row : dao.eventCounts(since)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", row.getDay() == null ? null : row.getDay().toString());
            item.put("name", row.getName());
            item.put("total", row.getTotal());
            item.put("users", row.getUsers());
            result.add(item);
        }
        return result;
    }

    /** 最近邻取法：第 p 百分位是排好序后位于 ceil(p% × n) 的那个值。 */
    static int percentile(List<Integer> sorted, int percent) {
        int index = (int) Math.ceil(percent / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.min(Math.max(index, 0), sorted.size() - 1));
    }

    /**
     * 只保留少量简单的属性。属性里不能放对话内容：值过长的文字被截断，嵌套结构直接丢弃。
     */
    private String props(Map<String, Object> props) {
        if (props == null || props.isEmpty()) {
            return null;
        }
        JSONObject kept = new JSONObject();
        for (Map.Entry<String, Object> entry : props.entrySet()) {
            if (kept.size() >= MAX_PROPS || entry.getKey() == null || !PROP_NAME.matcher(entry.getKey()).matches()) {
                continue;
            }
            Object value = entry.getValue();
            if (value instanceof Number || value instanceof Boolean) {
                kept.set(entry.getKey(), value);
            } else if (value instanceof String text) {
                kept.set(entry.getKey(), text.length() > MAX_PROP_TEXT ? text.substring(0, MAX_PROP_TEXT) : text);
            }
        }
        if (kept.isEmpty()) {
            return null;
        }
        String json = kept.toString();
        return json.length() > MAX_PROPS_LENGTH ? null : json;
    }
}
