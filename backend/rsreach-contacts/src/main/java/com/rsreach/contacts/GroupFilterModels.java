package com.rsreach.contacts;

import java.util.List;

public final class GroupFilterModels {

    private GroupFilterModels() {
    }

    public record FilterDefinition(int version, String match, List<Condition> conditions) {
    }

    public record Condition(String field, String op, Object value) {
    }
}
