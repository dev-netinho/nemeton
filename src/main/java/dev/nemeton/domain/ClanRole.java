package dev.nemeton.domain;

public enum ClanRole {
    LEADER, OFFICER, MEMBER;

    public boolean canManage() {
        return this == LEADER || this == OFFICER;
    }
}

