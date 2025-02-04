package com.tts.base.zookeeper;

import com.tts.base.enums.ServerState;
import com.tts.common.context.TtsContext;
import com.tts.framework.config.properties.ZkProperties;
import com.tts.base.service.BaseServerStateLogService;
import com.tts.common.utils.ip.IpUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.imps.CuratorFrameworkState;
import org.apache.curator.framework.recipes.cache.TreeCache;
import org.apache.curator.framework.recipes.cache.TreeCacheListener;
import org.apache.curator.framework.recipes.leader.LeaderSelector;
import org.apache.curator.framework.recipes.leader.LeaderSelectorListenerAdapter;
import org.apache.curator.retry.RetryForever;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.common.Time;
import org.apache.zookeeper.data.Stat;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.Closeable;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TTS服务节点，分 master 和 follower，借助zookeeper实现集群高可用
 * <p>
 * 实现Closeable为了更优雅的释放资源
 * 实现InitializingBean为了初始化节点字段信息
 *
 * @author FangYuan
 * @since 2022-12-26 15:05:18
 */
@Order(value = 0)
@Slf4j
@Component
public class TtsZkNode extends LeaderSelectorListenerAdapter implements Closeable, InitializingBean {

    @Autowired
    private ZkProperties nodeProperties;
    /**
     * 链接状态监听器
     */
    @Autowired
    private ZkConnectionStateListener connectionStateListener;
    @Autowired
    private BaseServerStateLogService baseServerStateLogService;

    /**
     * 节点服务名称 IP:currentTime
     */
    private String serviceName;

    /**
     * 当前节点路径，用来向zookeeper上注册
     */
    private String currentNodePath;

    private CuratorFramework curatorFramework;
    /**
     * 监听器
     */
    private LeaderSelector leaderSelector;
    /**
     * 节点监听器
     */
    private TreeCache treeCache;
    /**
     * 用于Leader节点的监听器
     */
    private volatile TreeCacheListener treeCacheListener;

    /**
     * 是否是Leader的标志位
     */
    private volatile AtomicBoolean isLeader;

    @Override
    public void afterPropertiesSet() {
        // 标记为普通节点
        isLeader = new AtomicBoolean(false);
        // 服务节点名: IP:currentTime
        serviceName = IpUtils.getHostIp() + ":" + Time.currentElapsedTime();
        TtsContext.setNodeServerName(serviceName);
        // treePath + serviceName
        currentNodePath = nodeProperties.getTreePath() + "/" + serviceName;

        RetryForever retryForever = new RetryForever(nodeProperties.getRetryCountInterval());
        curatorFramework = CuratorFrameworkFactory.newClient(nodeProperties.getAddress(),
                nodeProperties.getSessionTimeout(), nodeProperties.getConnectTimeout(), retryForever);

        leaderSelector = new LeaderSelector(curatorFramework, nodeProperties.getMasterPath(), this);
        treeCache = new TreeCache(curatorFramework, currentNodePath);

        /*
         当节点执行完takeLeadership()方法时，它会放弃Leader的身份
         此时Curator会从剩余的节点中再选出一个节点作为新的Leader
         调用autoRequeue()方法使放弃Leader身份的节点有机会重新成为Leader
         如果不执行该方法的话，那么该节点就不会再变成leader了
         */
        leaderSelector.autoRequeue();
    }

    /**
     * 当前节点被选中作为群首Leader时，会调用执行此方法
     * 注: 由于此方法结束后，对应的节点就会放弃leader，所以我们不能让此方法马上结束
     *
     * @param curatorFramework curator客户端
     */
    @Override
    public void takeLeadership(CuratorFramework curatorFramework) {
        try {
            log.info("TTS Node {} is Leader!", serviceName);

            isLeader.set(true);

            // 记录成为Leader的日志
            baseServerStateLogService.saveNewState(serviceName, ServerState.MASTER.getName());

            // 创建容器节点路径，这个treePath不存在不行
            Stat treePathStat = curatorFramework.checkExists().forPath(nodeProperties.getTreePath());
            if (treePathStat == null) {
                curatorFramework.create().creatingParentsIfNeeded()
                        .forPath(nodeProperties.getTreePath(), LocalDateTime.now().toString().getBytes(StandardCharsets.UTF_8));
            }

            // 注册路径子节点监听器
            registerPathWatcher();

            Thread.currentThread().join();
        } catch (Exception e) {
            log.error("error", e);

            Thread.currentThread().interrupt();
        } finally {
            log.info("TTS Node {} isn't Leader!", serviceName);

            isLeader.set(false);
            // 取消监听器
            unregisterPathWatcher();

            // 记录没有成为Leader的日志
            baseServerStateLogService.saveNewState(serviceName, ServerState.UN_MASTER.getName());
        }
    }

    /**
     * 注册路径子节点监听器
     */
    private void registerPathWatcher() {
        // 如果有了监听器先移除在重新添加注册
        if (treeCacheListener != null) {
            unregisterPathWatcher();
        }

        // 添加监听器
        this.treeCacheListener = new ZkTreeCacheListener(baseServerStateLogService, serviceName);
        treeCache.getListenable().addListener(treeCacheListener);

        try {
            treeCache.start();
        } catch (Exception e) {
            log.error("Start TreeCache Error", e);
        }
    }

    /**
     * 反注册该节点监听器
     */
    private void unregisterPathWatcher() {
        if (treeCacheListener != null) {
            treeCache.getListenable().removeListener(treeCacheListener);
        }
    }

    /**
     * TTS 节点启动方法
     */
    public void start() {
        log.info("TTS Node {} start!!!", serviceName);

        curatorFramework.start();
        leaderSelector.start();

        createNodePath();
        registerConnectionListener();

        log.info("TTS Node {} start over!!!", serviceName);
    }

    /**
     * 创建当前节点（临时），当断开连接时删除该节点，这样就能保证根路径下永远都是已连接状态的服务
     *
     * treePath/serverName1, treePath/serverName2...
     */
    private void createNodePath() {
        try {
            Stat treePathStat = curatorFramework.checkExists().forPath(currentNodePath);
            if (treePathStat == null) {
                curatorFramework.create().creatingParentsIfNeeded().withMode(CreateMode.EPHEMERAL)
                        .forPath(currentNodePath, LocalDateTime.now().toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 注册链接监听器
     */
    private void registerConnectionListener() {
        while (true) {
            try {
                log.info("TTS Node {} register!!!", serviceName);

                // 添加链接状态监听器
                curatorFramework.getConnectionStateListenable().addListener(connectionStateListener);

                log.info("TTS Node {} register over!!!", serviceName);
                break;
            } catch (Exception e) {
                log.error("TTS Node " + serviceName + " register!!!", e);
            }

            // 休息一秒后重试
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                log.error("TTS Node " + serviceName + " sleep error!!!", e);
            }
        }
    }

    @Override
    public void close() {
        log.info("TTS Node {} close!!!", serviceName);

        try {
            treeCache.close();
            treeCache = null;
        } catch (Exception e) {
            log.error("Close TreeCache Error", e);
        }

        try {
            leaderSelector.close();
            leaderSelector = null;
        } catch (Exception e) {
            log.error("Close LeaderSelector Error", e);
        }

        try {
            curatorFramework.close();
            curatorFramework = null;
        } catch (Exception e) {
            log.error("Close CuratorFramework Error", e);
        }

        log.info("TTS Node {} close over!!!", serviceName);
    }

    /**
     * 获取该路径下注册的所有服务名，只有Leader节点会调用该方法
     *
     * etc: [serverName1, serverName2...]
     */
    public List<String> getServerNameList() {
        try {
            if (isLiving()) {
                List<String> serverNames = curatorFramework.getChildren().forPath(nodeProperties.getTreePath());
                // 移除主节点的服务名
                serverNames.remove(serviceName);

                return serverNames;
            }
            return Collections.emptyList();
        } catch (Exception e) {
            log.error("TTS Node getAllServerIpList error !!!", e);

            return Collections.emptyList();
        }
    }

    /**
     * 是活着的状态？
     */
    public boolean isLiving() {
        return curatorFramework != null && CuratorFrameworkState.STARTED.equals(curatorFramework.getState());
    }

    /**
     * 判断该节点是不是Leader
     */
    public AtomicBoolean getIsLeader() {
        return isLeader;
    }

    public String getServiceName() {
        return serviceName;
    }
}
