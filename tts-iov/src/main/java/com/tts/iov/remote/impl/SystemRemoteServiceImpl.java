package com.tts.iov.remote.impl;

import com.tts.iov.domain.IovConfig;
import com.tts.iov.service.IovConfigService;
import com.tts.remote.dto.IovConfigDto;
import com.tts.remote.service.SystemRemoteService;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
@DubboService
public class SystemRemoteServiceImpl implements SystemRemoteService {

    @Autowired
    private IovConfigService iovConfigService;

    @Override
    public boolean saveOrUpdateIovConfig(IovConfigDto iovConfig) {
        return iovConfigService.saveOrUpdateIovConfig(new IovConfig(iovConfig.getIovType(), iovConfig.getConfigInfo()));
    }
}
