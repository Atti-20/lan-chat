package com.lanchat.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MentionReceiptMembershipBoundaryContractTest {

    @Test
    void receiptQueriesUseTheMembershipSequenceFloorAndRejoinUpsertResetsIt() throws Exception {
        String selectSql = String.join("\n", ChatMessageMapper.class
                .getMethod("selectUnrecordedMentionedMessagesReadBy", String.class, Long.class, Long.class)
                .getAnnotation(Select.class)
                .value());
        String insertSql = String.join("\n", ChatMessageMapper.class
                .getMethod("recordMentionReceiptsRead", String.class, Long.class, Long.class)
                .getAnnotation(Insert.class)
                .value());
        String membershipSql = String.join("\n", ConversationMemberMapper.class
                .getMethod("insertIfAbsent", String.class, Long.class, String.class)
                .getAnnotation(Insert.class)
                .value());

        assertTrue(selectSql.contains("message.sequence >= COALESCE(member.receipt_start_sequence, 1)"));
        assertTrue(insertSql.contains("message.sequence >= COALESCE(member.receipt_start_sequence, 1)"));
        assertTrue(membershipSql.contains("receipt_start_sequence = IF("));
        assertTrue(membershipSql.contains("VALUES(receipt_start_sequence)"));
    }
}
