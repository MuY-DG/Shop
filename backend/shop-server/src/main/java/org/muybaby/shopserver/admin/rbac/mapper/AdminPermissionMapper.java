package org.muybaby.shopserver.admin.rbac.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.muybaby.shopserver.admin.rbac.entity.AdminPermission;

@Mapper
public interface AdminPermissionMapper extends BaseMapper<AdminPermission> {
}
