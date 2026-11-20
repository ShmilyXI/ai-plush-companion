package zixuan.modules.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import zixuan.modules.analytics.dao.AnalyticsDao;
import zixuan.modules.analytics.dao.AnalyticsDao.ActivityRow;
import zixuan.modules.analytics.dao.AnalyticsDao.EventCountRow;
import zixuan.modules.analytics.dao.AnalyticsDao.LatencyRow;
import zixuan.modules.analytics.dao.AnalyticsDao.SignupRow;
import zixuan.modules.analytics.dto.ClientEventsDTO;
import zixuan.modules.analytics.service.impl.AnalyticsServiceImpl;

class AnalyticsServiceImplTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final AnalyticsDao dao = mock(AnalyticsDao.class);
    private final AnalyticsServiceImpl service = new AnalyticsServiceImpl(dao);

    @Test
    void recordsWellFormedEventsAndKeepsOnlySimpleProperties() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("reason", "NotAllowedError");
        props.put("attempt", 3);
        props.put("nested", Map.of("text", "用户说的话"));
        props.put("Bad-Key", "x");
        props.put("long_text", "很长".repeat(100));

        int recorded = service.recordEvents(9L, events(event("mic_failed", props), event("Not Valid", null),
                event(null, null), event("quota_reached", null)));

        assertEquals(2, recorded);
        ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
        verify(dao).insertEvent(any(LocalDate.class), eq(9L), eq("mic_failed"), stored.capture());
        assertEquals(true, stored.getValue().contains("\"reason\":\"NotAllowedError\""));
        assertEquals(true, stored.getValue().contains("\"attempt\":3"));
        assertEquals(false, stored.getValue().contains("nested"));
        assertEquals(false, stored.getValue().contains("Bad-Key"));
        // 过长的文字被截断，属性里放不下一段对话。
        assertEquals(true, stored.getValue().length() < 300);
        verify(dao).insertEvent(any(LocalDate.class), eq(9L), eq("quota_reached"), isNull());
    }

    @Test
    void oneRequestCannotFloodTheTable() {
        List<ClientEventsDTO.Item> many = new ArrayList<>();
        for (int index = 0; index < 50; index++) {
            many.add(event("page_view", null));
        }
        ClientEventsDTO dto = new ClientEventsDTO();
        dto.setEvents(many);

        assertEquals(20, service.recordEvents(9L, dto));
        verify(dao, times(20)).insertEvent(any(), any(), any(), any());

        assertEquals(0, service.recordEvents(null, dto));
        assertEquals(0, service.recordEvents(9L, null));
    }

    @Test
    void retentionCountsExactlyDayNAndLeavesUnfinishedDaysBlank() {
        LocalDate today = LocalDate.now(ZONE);
        LocalDate tenDaysAgo = today.minusDays(10);
        LocalDate yesterday = today.minusDays(1);
        when(dao.signups(any(Date.class))).thenReturn(List.of(
                signup(1L, tenDaysAgo), signup(2L, tenDaysAgo), signup(3L, tenDaysAgo), signup(4L, yesterday)));
        when(dao.activeDays(any(LocalDate.class))).thenReturn(List.of(
                new ActivityRow(1L, tenDaysAgo), new ActivityRow(1L, tenDaysAgo.plusDays(1)),
                new ActivityRow(1L, tenDaysAgo.plusDays(7)),
                // 用户 2 第 2 天和第 8 天回来过，但不是第 1 天和第 7 天，不算进对应的留存。
                new ActivityRow(2L, tenDaysAgo.plusDays(2)), new ActivityRow(2L, tenDaysAgo.plusDays(8)),
                new ActivityRow(4L, yesterday)));
        when(dao.firstTextTimes(any(LocalDate.class))).thenReturn(List.of());
        when(dao.eventCounts(any(LocalDate.class))).thenReturn(List.of());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cohorts = (List<Map<String, Object>>) service.overview(30).get("cohorts");

        // 最新的注册日期排在前面。
        Map<String, Object> recent = cohorts.get(0);
        assertEquals(yesterday.toString(), recent.get("date"));
        assertEquals(1, recent.get("size"));
        assertEquals(1L, recent.get("activated"));
        assertNull(recent.get("d1"));
        assertNull(recent.get("d7"));

        Map<String, Object> older = cohorts.get(1);
        assertEquals(tenDaysAgo.toString(), older.get("date"));
        assertEquals(3, older.get("size"));
        assertEquals(2L, older.get("activated"));
        assertEquals(1L, older.get("d1"));
        assertEquals(1L, older.get("d7"));
        assertNull(older.get("d30"));
    }

    @Test
    void latencyIsSummarisedPerDayByMedianAndNinetiethPercentile() {
        LocalDate today = LocalDate.now(ZONE);
        List<LatencyRow> rows = new ArrayList<>();
        for (int value : new int[] { 900, 300, 500, 700, 100, 1100, 1300, 1500, 1700, 5000 }) {
            rows.add(new LatencyRow(today, value));
        }
        rows.add(new LatencyRow(today.minusDays(1), 640));
        when(dao.signups(any(Date.class))).thenReturn(List.of());
        when(dao.activeDays(any(LocalDate.class))).thenReturn(List.of());
        when(dao.firstTextTimes(any(LocalDate.class))).thenReturn(rows);
        when(dao.eventCounts(any(LocalDate.class))).thenReturn(List.of(new EventCountRow(today, "mic_failed", 4L, 2L)));

        Map<String, Object> overview = service.overview(14);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> latency = (List<Map<String, Object>>) overview.get("latency");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) overview.get("events");

        assertEquals(today.toString(), latency.get(0).get("date"));
        assertEquals(10, latency.get(0).get("turns"));
        assertEquals(900, latency.get(0).get("p50"));
        assertEquals(1700, latency.get(0).get("p90"));
        assertEquals(640, latency.get(1).get("p50"));
        assertEquals(640, latency.get(1).get("p90"));
        assertEquals("mic_failed", events.get(0).get("name"));
        assertEquals(4L, events.get(0).get("total"));
        assertEquals(2L, events.get(0).get("users"));
        verify(dao, never()).insertEvent(any(), any(), any(), any());
    }

    private SignupRow signup(Long userId, LocalDate day) {
        // 当天中午注册，按上海时间落在这一天。
        return new SignupRow(userId, Date.from(day.atTime(12, 0).atZone(ZONE).toInstant()));
    }

    private ClientEventsDTO events(ClientEventsDTO.Item... items) {
        ClientEventsDTO dto = new ClientEventsDTO();
        dto.setEvents(List.of(items));
        return dto;
    }

    private ClientEventsDTO.Item event(String name, Map<String, Object> props) {
        ClientEventsDTO.Item item = new ClientEventsDTO.Item();
        item.setName(name);
        item.setProps(props);
        return item;
    }
}
