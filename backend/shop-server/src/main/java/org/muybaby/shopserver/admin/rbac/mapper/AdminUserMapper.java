package org.muybaby.shopserver.admin.rbac.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.muybaby.shopserver.admin.rbac.entity.AdminUser;

@Mapper
public interface AdminUserMapper extends BaseMapper<AdminUser> {
}
