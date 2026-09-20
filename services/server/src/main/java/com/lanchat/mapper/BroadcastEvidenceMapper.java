package com.lanchat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanchat.entity.BroadcastEvidence;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface BroadcastEvidenceMapper extends BaseMapper<BroadcastEvidence> {

    @Select("""
            SELECT *
            FROM broadcast_evidence
            WHERE broadcast_id = #{broadcastId}
              AND receiver_id = #{receiverId}
            ORDER BY id
            """)
    List<BroadcastEvidence> selectByReceiver(
            @Param("broadcastId") Long broadcastId,
            @Param("receiverId") Long receiverId
    );

    @Select("""
            SELECT *
            FROM broadcast_evidence
            WHERE broadcast_id = #{broadcastId}
              AND receiver_id IS NULL
            ORDER BY id
            """)
    List<BroadcastEvidence> selectContentEvidence(
            @Param("broadcastId") Long broadcastId
    );
}
