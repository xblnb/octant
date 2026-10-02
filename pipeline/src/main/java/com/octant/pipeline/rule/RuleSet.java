package com.octant.pipeline.rule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class RuleSet {

    private final List<Rule> rules;
    private final Map<String, Rule.Condition> conditions;

    private RuleSet(List<Rule> rules, Map<String, Rule.Condition> conditions) {
        this.rules = List.copyOf(rules);
        this.conditions = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(conditions));
    }

    public static Builder builder() {
        return new Builder();
    }

    public List<Rule> rules() {
        return rules;
    }

    public record Evaluation(List<Rule.Hit> trace, Map<String, Rule> effective,
                             List<String> suppressedShadowed, Set<String> matchedIds) {
        public Rule.Hit hitOf(String ruleId) {
            for (Rule.Hit h : trace) {
                if (h.ruleId().equals(ruleId)) {
                    return h;
                }
            }
            return null;
        }

        public int matchedCount() {
            return matchedIds.size();
        }
    }

    public Evaluation evaluate(Map<String, Object> features) {
        List<Rule> ordered = new ArrayList<>(rules);
        ordered.sort(Comparator.comparingInt(Rule::priority).reversed().thenComparing(Rule::id));

        List<Rule.Hit> trace = new ArrayList<>();
        Map<String, Rule> effective = new LinkedHashMap<>();
        List<String> shadowed = new ArrayList<>();
        Set<String> matched = new LinkedHashSet<>();

        for (Rule rule : ordered) {
            Rule.Condition cond = conditions.get(rule.id());
            boolean ok = cond != null && cond.test(features);
            Map<String, Object> observed = new LinkedHashMap<>();
            observed.put("group", rule.group());
            observed.put("priority", (long) rule.priority());
            trace.add(new Rule.Hit(rule.id(), rule.group(), rule.priority(), ok,
                    ok ? rule.actionKey() : "no-match:" + rule.conditionKey(), observed));
            if (!ok) {
                continue;
            }
            matched.add(rule.id());
            Rule existing = effective.get(rule.group());
            if (existing == null) {
                effective.put(rule.group(), rule);
            } else {
                shadowed.add(rule.id() + " shadowed by " + existing.id() + " in group " + rule.group());
            }
        }
        return new Evaluation(List.copyOf(trace),
                java.util.Collections.unmodifiableMap(new LinkedHashMap<>(effective)),
                List.copyOf(shadowed),
                Collections.unmodifiableSet(matched));
    }

    public static final class Builder {

        private final List<Rule> rules = new ArrayList<>();
        private final Map<String, Rule.Condition> conditions = new LinkedHashMap<>();

        public Builder rule(Rule rule, Rule.Condition condition) {
            if (rules.stream().anyMatch(r -> r.id().equals(rule.id()))) {
                throw new IllegalArgumentException("规则 ID 重复：" + rule.id());
            }
            rules.add(rule);
            conditions.put(rule.id(), condition);
            return this;
        }

        public RuleSet build() {
            return new RuleSet(rules, conditions);
        }
    }
}
