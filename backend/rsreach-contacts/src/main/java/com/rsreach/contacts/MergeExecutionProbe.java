package com.rsreach.contacts;

/**
 * Optional test/production hook invoked after merge mutations and immediately
 * before persisting the {@code contact_merges} row. Production has no bean;
 * tests may register one to force rollback without a public failure API.
 */
@FunctionalInterface
public interface MergeExecutionProbe {

    void beforeMergeRecordPersist();
}
