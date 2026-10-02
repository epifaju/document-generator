package com.adgendoc.domain;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public enum RequestStatus {

    DRAFT,
    MISSING_INFORMATION,
    VALIDATED,
    REJECTED,
    GENERATED,
    FAILED;

    private static final Map<RequestStatus, Set<RequestStatus>> ALLOWED_TRANSITIONS =
            new EnumMap<>(RequestStatus.class);

    static {
        addTransitions(DRAFT, VALIDATED, MISSING_INFORMATION, REJECTED);
        addTransitions(MISSING_INFORMATION, VALIDATED, REJECTED, MISSING_INFORMATION);
        addTransitions(VALIDATED, MISSING_INFORMATION, GENERATED, FAILED, REJECTED, VALIDATED);
        addTransitions(FAILED, VALIDATED, REJECTED, MISSING_INFORMATION);
    }

    private static void addTransitions(RequestStatus source, RequestStatus... targets) {
        EnumSet<RequestStatus> transitions = EnumSet.noneOf(RequestStatus.class);
        for (RequestStatus target : targets) {
            transitions.add(target);
        }
        ALLOWED_TRANSITIONS.put(source, transitions);
    }

    public boolean isTerminal() {
        return this == REJECTED || this == GENERATED;
    }

    public boolean canBeModified() {
        return !isTerminal();
    }

    public boolean canGenerate() {
        return this == VALIDATED;
    }

    public boolean canTransitionTo(RequestStatus target) {
        return allowedTransitions().contains(target);
    }

    public Set<RequestStatus> allowedTransitions() {
        Set<RequestStatus> transitions = ALLOWED_TRANSITIONS.get(this);
        return transitions == null
                ? Collections.emptySet()
                : Collections.unmodifiableSet(transitions);
    }
}
