package com.admindi.backend.model;

import java.io.Serializable;
import java.util.Objects;

public class OwnerMembershipId implements Serializable {
    private String userId;
    private String ownerId;

    public OwnerMembershipId() {}

    public OwnerMembershipId(String userId, String ownerId) {
        this.userId = userId;
        this.ownerId = ownerId;
    }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        OwnerMembershipId that = (OwnerMembershipId) o;
        return Objects.equals(userId, that.userId) && Objects.equals(ownerId, that.ownerId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, ownerId);
    }
}
