package com.fayayo.job.core.executor;

import com.fayayo.job.common.constants.Constants;
import com.fayayo.job.common.enums.ResultEnum;
import com.fayayo.job.common.exception.CommonException;
import com.fayayo.job.core.annotation.FayaService;
import com.fayayo.job.core.executor.handler.JobExecutorHandler;
import com.fayayo.job.core.service.impl.ZkServiceRegistry;
import com.fayayo.job.core.thread.CallbackThread;
import com.fayayo.job.core.transport.NettyServer;
import com.fayayo.job.core.zookeeper.ZKCuratorClient;
import com.fayayo.job.core.zookeeper.ZkProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.util.CollectionUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

/**
 * @author dalizu on 2018/8/22.
 * @version v1.0
 * @desc Task执行器 注册，获取服务等功能
 */
@Slf4j
public class JobExecutor implements ApplicationContextAware {

    private static ApplicationContext applicationContext;

    private static ConcurrentHashMap<String, Object> service = new ConcurrentHashMap<String, Object>();

    @Autowired
    private ZKCuratorClient zkCuratorClient;

    @Autowired
    private ZkProperties zkProperties;

    private String server;

    private Integer port;

    private Integer weight;

    private String name;

    private static String mainClass;

    public JobExecutor() {
    }

    public JobExecutor(String server, Integer port, Integer weight, String name, String mainClass) {
        this.server = server;
        this.port = port;
        this.weight = weight;
        this.name = name;
        if (this.weight == null) {
            this.weight = 1;//如果不写默认全是1
        }
        this.mainClass = mainClass;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
        initRpcService();//初始化RPC服务
        initServer();//启动服务端
        initRegister();//注册服务

        //启动结果处理线程
        CallbackThread.getInstance().start();

    }

    /**
     * @描述 服务发现 把RPC的服务保存起来
     */
    private void initRpcService() {
        Map<String, Object> services = this.applicationContext.getBeansWithAnnotation(FayaService.class);//根据注解获取所有的service
        if (!CollectionUtils.isEmpty(services)) {

            for (Object bean : services.values()) {
                if (bean.getClass().isAnnotationPresent(FayaService.class)) {
                    FayaService fayaService = bean.getClass().getAnnotation(FayaService.class);
                    Class<?> clazz = fayaService.value();
                    log.info("{}Registering service '{}' for RPC result [{}].", Constants.LOG_PREFIX, clazz.getName(), bean);
                    service.put(clazz.getName(), bean);//保存映射关系
                }
            }
        }
    }

    /**
     * @描述 根据配置获取具体执行的服务类
     */
    public static JobExecutorHandler getHandler() {
        Object object = service.get(mainClass);
        if (object == null) {
            throw new CommonException(ResultEnum.JOB_HANDLER_ERROR);
        }
        return (JobExecutorHandler) object;
    }

    private void initServer() {
        log.info("{}执行器初始化start......:{}", Constants.LOG_PREFIX, server);
        //然后启动这个服务端，准备接收请求
        CountDownLatch countDownLatch = new CountDownLatch(1);//阻塞线程
        NettyServer nettyServer = new NettyServer(port);
        nettyServer.start(countDownLatch);
        try {
            countDownLatch.await();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        log.info("{}服务端启动完毕,开始注册到服务中心", Constants.LOG_PREFIX);
    }

    private void initRegister() {
        //服务端启动成功后，注册此执行器的这个服务到zk
        ZkServiceRegistry zkServiceRegistry = new ZkServiceRegistry(zkCuratorClient, zkProperties);
        String serviceAddress = new StringBuilder().
                append(server).append(":").
                append(port).append(":").
                append(weight).toString();
        zkServiceRegistry.register(name, serviceAddress);
        log.info("{}注册到服务中心完成", Constants.LOG_PREFIX);
    }

}
