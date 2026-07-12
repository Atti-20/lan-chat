package com.lanchat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanchat.entity.Friendship;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface FriendshipMapper extends BaseMapper<Friendship> {
}
