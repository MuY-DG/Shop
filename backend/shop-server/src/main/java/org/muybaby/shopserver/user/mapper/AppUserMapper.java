package org.muybaby.shopserver.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.muybaby.shopserver.user.entity.AppUser;

@Mapper
public interface AppUserMapper extends BaseMapper<AppUser> {
}
