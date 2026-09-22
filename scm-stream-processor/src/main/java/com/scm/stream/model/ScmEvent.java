package com.scm.stream.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.HashMap;
import java.util.Map;

/** Mirrors com.scm.core.event.SupplyChainEvent. Public fields + no-arg ctor make it a Flink POJO. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ScmEvent {

    public String eventId;
    public String type;
    public String source;
    public String entityId;
    public String timestamp;
    public Map<String, Object> data = new HashMap<>();

    public ScmEvent() {}

    public String str(String key) {
        Object v = data.get(key);
        return v == null ? null : v.toString();
    }

    public int integer(String key) {
        Object v = data.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        return v == null ? 0 : Integer.parseInt(v.toString());
    }

    @Override
    public String toString() {
        return type + "(" + entityId + ")" + data;
    }
}
