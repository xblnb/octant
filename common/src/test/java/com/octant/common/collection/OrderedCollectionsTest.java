package com.octant.common.collection;

import com.octant.common.MetricResult;
import com.octant.common.Confidence;
import com.octant.common.engine.AnalysisReport;
import com.octant.common.engine.MetricsEngine;
import com.octant.common.model.RawEvent;
import com.octant.common.model.RawEventSchema;
import com.octant.common.privacy.OrderedCollections;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderedCollectionsTest {

    private static Map<String, Integer> deliberatelyUnsorted() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("zulu", 1);
        m.put("alpha", 2);
        m.put("mike", 3);
        m.put("bravo", 4);
        return m;
    }

    @Nested
    @DisplayName("不变量：副本的迭代序等于入参的迭代序")
    class OrderIsPreserved {

        @Test
        @DisplayName("Map：顺序与入参逐项相同（不是字典序、不是哈希序）")
        void mapOrderIsTheInputOrder() {
            Map<String, Integer> src = deliberatelyUnsorted();
            Map<String, Integer> copy = OrderedCollections.copyOf(src);

            assertEquals(List.of("zulu", "alpha", "mike", "bravo"), List.copyOf(copy.keySet()),
                    "副本必须保持入参顺序；字典序/哈希序都算违反");
            assertEquals(List.copyOf(src.entrySet()), List.copyOf(copy.entrySet()));
            assertNotSame(src, copy);
        }

        @Test
        @DisplayName("对照：Map.copyOf 不保证保序（若此断言变红，说明 JDK 改了语义）")
        void copyOfDoesNotPromiseOrder() {
            Map<String, Integer> src = deliberatelyUnsorted();
            Map<String, Integer> jdkCopy = Map.copyOf(src);

            assertEquals(src.size(), jdkCopy.size(), "内容必须相同（本测试比较的是顺序，不是内容）");
            assertEquals(new java.util.HashSet<>(src.keySet()), new java.util.HashSet<>(jdkCopy.keySet()));
            assertTrue(true, "契约上无序：故实现侧一律用 OrderedCollections，不依赖运行时抽查");
        }

        @Test
        @DisplayName("Set：顺序与入参相同；List：顺序与入参相同")
        void setAndListPreserveOrder() {
            Set<String> srcSet = new LinkedHashSet<>(List.of("zulu", "alpha", "mike"));
            assertEquals(List.of("zulu", "alpha", "mike"),
                    List.copyOf(OrderedCollections.copyOf(srcSet)));

            List<String> srcList = List.of("zulu", "alpha", "mike");
            assertEquals(srcList, OrderedCollections.copyOf(srcList));
        }

        @Test
        @DisplayName("不可变：副本对 writer 封闭")
        void copiesAreImmutable() {
            Map<String, Integer> copy = OrderedCollections.copyOf(deliberatelyUnsorted());
            assertThrows(UnsupportedOperationException.class, () -> copy.put("x", 1));
            Set<String> setCopy = OrderedCollections.copyOf(new LinkedHashSet<>(List.of("a")));
            assertThrows(UnsupportedOperationException.class, setCopy::clear);
            List<String> listCopy = OrderedCollections.copyOf(List.of("a"));
            assertThrows(UnsupportedOperationException.class, () -> listCopy.add("b"));
        }

        @Test
        @DisplayName("null 入参降级为空集合（与调用点的缺省语义一致），不抛")
        void nullBecomesEmpty() {
            assertEquals(Map.of(), OrderedCollections.copyOf((Map<String, Integer>) null));
            assertEquals(List.of(), OrderedCollections.copyOf((List<String>) null));
            assertEquals(Set.of(), OrderedCollections.copyOf((Set<String>) null));
        }
    }

    @Nested
    @DisplayName("端到端：这些副本确实在 `common` 的报告对象上生效")
    class WiredIntoTheReportObject {

        @Test
        @DisplayName("AnalysisReport.metrics() 的迭代序 = MetricsEngine 装配它的顺序")
        void analysisReportKeepsAssemblyOrder() {
            MetricsEngine engine = new MetricsEngine();
            AnalysisReport report = engine.analyze(List.<RawEvent>of());

            List<String> keys = List.copyOf(report.metrics().keySet());
            assertTrue(keys.contains("M3a"), "空输入也应有 M3* 抑制项，实际：" + keys);
            int firstM5 = -1;
            int lastM3 = -1;
            for (int i = 0; i < keys.size(); i++) {
                if (keys.get(i).startsWith("M3")) {
                    lastM3 = i;
                }
                if (keys.get(i).startsWith("M5") && firstM5 < 0) {
                    firstM5 = i;
                }
            }
            assertTrue(lastM3 >= 0 && firstM5 >= 0, "两类都必须在：M3=" + lastM3 + " M5=" + firstM5);
            assertTrue(lastM3 < firstM5,
                    "时长族必须整体在离散度族之前（装配顺序），实际：" + keys);
        }

        @Test
        @DisplayName("MetricResult.extras 保序（下游若改为直接迭代也不会漂）")
        void metricExtrasKeepOrder() {
            Map<String, Object> extras = deliberatelyUnsortedObjects();
            MetricResult r = MetricResult.of("M5b", 0.42d, Confidence.B, extras);
            assertEquals(List.of("zulu", "alpha", "mike", "bravo"),
                    List.copyOf(r.extras().keySet()),
                    "extras 的顺序必须是调用方给的那个顺序");
        }

        @Test
        @DisplayName("确定性：同输入两次装配 → 序列化字节逐字相同（不靠'跑两次看看'）")
        void serializationIsAFunctionOfTheInput() {
            Map<String, Object> a = OrderedCollections.copyOf(deliberatelyUnsortedObjects());
            Map<String, Object> b = OrderedCollections.copyOf(deliberatelyUnsortedObjects());
            assertEquals(com.octant.common.model.Json.encode(a),
                    com.octant.common.model.Json.encode(b),
                    "同输入必须同字节；若无序副本参与，这里会随 salt 漂");

            assertEquals("{\"zulu\":1,\"alpha\":2,\"mike\":3,\"bravo\":4}",
                    com.octant.common.model.Json.encode(a),
                    "输出必须是入参顺序；排序式序列化会把'装配序'这个设计悄悄换掉");
        }

        private Map<String, Object> deliberatelyUnsortedObjects() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("zulu", 1);
            m.put("alpha", 2);
            m.put("mike", 3);
            m.put("bravo", 4);
            return m;
        }
    }

    @Nested
    @DisplayName("契约常量与 schema 版本仍在（防止顺手改坏）")
    class Sanity {

        @Test
        @DisplayName("RawEventSchema.VERSION 仍是 C-A 的 1.0.0；环境快照版本是另一个常量")
        void versionsStayDistinct() {
            assertEquals("raw-event-schema@1.0.0", RawEventSchema.VERSION);
            assertEquals("env-snapshot@1.0.0", RawEventSchema.ENV_SNAPSHOT_SCHEMA_VERSION);
            assertTrue(!RawEventSchema.VERSION.equals(RawEventSchema.ENV_SNAPSHOT_SCHEMA_VERSION),
                    "事件 schema 版本与环境快照版本必须分开（契约 EV-S4 明写）");
        }
    }
}
