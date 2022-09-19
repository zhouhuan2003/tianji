package com.tianji.auth.task;

import cn.hutool.core.collection.CollectionUtil;
import com.tianji.auth.common.constants.JwtConstants;
import com.tianji.auth.common.domain.PrivilegeRoleDTO;
import com.tianji.auth.service.IPrivilegeService;
import com.tianji.auth.util.PrivilegeCache;
import com.tianji.common.utils.RequestIdUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;


@Slf4j
@Component
@RequiredArgsConstructor
public class LoadPrivilegeRunner{

    private final IPrivilegeService privilegeService;
    private final PrivilegeCache privilegeCache;
    private final RedissonClient redissonClient;

    @Scheduled(fixedRate = 180000)
    public void loadPrivilegeCache(){
        // 标记requestId
        RequestIdUtil.markRequest();
        // 1.获取锁
        RLock lock = redissonClient.getLock(JwtConstants.LOCK_AUTH_PRIVILEGE_KEY);
        boolean isLock = lock.tryLock();
        if (!isLock) {
            // 获取锁失败，结束
            return;
        }
        try {
            log.trace("开始更新权限缓存数据");
            // 1.查询数据
            List<PrivilegeRoleDTO> privilegeRoleDTOS = privilegeService.listPrivilegeRoles();
            if (CollectionUtil.isEmpty(privilegeRoleDTOS)) {
                return;
            }
            // 2.缓存
            privilegeCache.initPrivilegesCache(privilegeRoleDTOS);
            log.trace("更新权限缓存数据成功！");
        }catch (Exception e){
            log.error("更新权限缓存数据失败！原因：{}", e.getMessage());
        }finally {
            // 释放锁
            lock.unlock();
            RequestIdUtil.clear();
        }
    }
}